package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameActionRejectedException;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.scheduling.DeadlineHandler;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Phase 13D(#6) — 개인 턴 제한 시간 초과 시 자동으로 안전 액션을 적용해 다음
 * 순서로 넘긴다.
 *
 * <p>구조는 {@link BotScheduler} 와 동일한 lock 공유 패턴. 차이: 가상스레드 즉시
 * 실행이 아니라 Redis 데드라인 큐의 지연 실행.
 *
 * <p>매 액션 적용 후 (인간/봇/타임아웃) {@link #onTurnAdvanced(String)} 가 호출되어
 * per-room generation 을 증가시키고 새 타이머를 (re)스케줄한다. 발화된 task 는
 * 캡처한 generation 이 현재와 다르면 abort — 그 사이 누군가 행동했다는 뜻
 * (중복 자동행동 방지). {@link RoomActionLock} 2초 TTL 로 human/bot/timeout 3자
 * 경합을 직렬화.
 *
 * <p>D-96 — 타이머를 in-memory {@code ScheduledFuture} 에서 <b>Redis 데드라인 큐</b>로
 * 옮겼다. 두 방어가 서로 다른 경합을 담당한다: {@link DeadlineQueue} 의 원자 pop 이
 * "두 인스턴스가 같은 타이머를 잡는 것"을, {@link RoomGeneration} 이 "pop 과 락 획득
 * 사이에 누가 행동한 것"을 막는다. 구 generation 은 in-memory 라 2인스턴스에서는
 * 가드 자체가 작동하지 않았다.
 *
 * <p>D-98 — 게임을 모른다. 겨눌 좌석은 {@link GameEngine#pendingSeat}, 적용할 안전
 * 액션은 {@link GameEngine#timeoutAction} 이 결정한다 (과거 이 클래스가 티츄 단계별
 * switch 와 {@code TimeoutActionPolicy} 를 직접 들고 있었다).
 */
@Component
public class TurnTimeoutScheduler implements DeadlineHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnTimeoutScheduler.class);

    /** `deadlines:turn` 큐. */
    public static final String KIND = "turn";
    /** 락 경합 시 재시도 간격 — 폴링 주기보다 짧게 잡아 즉시 다음 사이클에 걸리게. */
    private static final Duration LOCK_RETRY = Duration.ofMillis(200);

    private final RoomService roomService;
    private final GameEngineProvider engines;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final BotScheduler botScheduler;
    private final DeadlineQueue deadlines;
    private final RoomGeneration generations;

    public TurnTimeoutScheduler(RoomService roomService,
                                GameEngineProvider engines,
                                GameEventBroadcaster broadcaster,
                                RoomActionLock lock,
                                MatchProgressService matchProgress,
                                @Lazy BotScheduler botScheduler,
                                DeadlineQueue deadlines,
                                RoomGeneration generations) {
        this.roomService = roomService;
        this.engines = engines;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.botScheduler = botScheduler;
        this.deadlines = deadlines;
        this.generations = generations;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /**
     * 턴이 진행됐을 때 (액션 적용 직후) 호출. generation++ 후 turnSeconds>0 이면
     * 타이머 재스케줄. turnSeconds=0 (끔) 이면 기존 타이머만 취소하고 종료.
     *
     * <p>member 에 generation 을 실어 보내므로 이전 generation 항목은 발화해도
     * gen 불일치로 버려진다. 그래도 ZSET 을 작게 유지하려고 명시적으로 취소한다.
     */
    public void onTurnAdvanced(String roomId) {
        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            cleanup(roomId);
            return;
        }
        long prevGen = generations.current(roomId);
        long gen = generations.bump(roomId);
        deadlines.cancel(KIND, member(roomId, prevGen));

        int turnSeconds = room.turnSeconds();
        if (turnSeconds <= 0) return;  // 타이머 끔 — 기존 동작 호환.

        deadlines.schedule(KIND, member(roomId, gen), Duration.ofSeconds(turnSeconds));
    }

    /** 폴러가 만료된 항목을 넘겨준다. 이 인스턴스가 단독 소유한 상태로 들어온다. */
    @Override
    public void handle(String member) {
        int sep = member.lastIndexOf('#');
        if (sep < 0) {
            log.warn("턴 데드라인 member 형식 오류: {}", member);
            return;
        }
        String roomId = member.substring(0, sep);
        long gen;
        try {
            gen = Long.parseLong(member.substring(sep + 1));
        } catch (NumberFormatException e) {
            log.warn("턴 데드라인 generation 파싱 실패: {}", member);
            return;
        }
        fire(roomId, gen);
    }

    private void fire(String roomId, long capturedGen) {
        // 그 사이 누가 행동했으면 generation 이 올라가 있다 — 버린다.
        if (generations.current(roomId) != capturedGen) return;

        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            cleanup(roomId);
            return;
        }

        if (!lock.tryAcquire(roomId)) {
            // 다른 액션 처리 중 — 짧게 뒤로 미뤄 재시도 (gen 재확인은 그때).
            deadlines.schedule(KIND, member(roomId, capturedGen), LOCK_RETRY);
            return;
        }
        boolean acted = false;
        try {
            // 락 안에서 gen 재확인 (락 대기 중 누가 행동했을 수 있음).
            if (generations.current(roomId) != capturedGen) return;

            GameEngine engine = engines.forRoom(room);
            GameState state = engine.loadState().orElse(null);
            if (state == null || engine.isRoundOver(state)) return;

            int seat = engine.pendingSeat(state);
            if (seat < 0) return;

            GameAction action = engine.timeoutAction(state, seat);
            if (action == null) {
                log.warn("Turn timeout: no legal action. roomId={} seat={} phase={}",
                        roomId, seat, engine.phaseName(state));
                return;
            }
            applyAndBroadcast(roomId, room, engine, seat, action, state);
            acted = true;
            log.info("Turn timeout auto-action: roomId={} seat={} action={}",
                    roomId, seat, action.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("TurnTimeoutScheduler error in room {}: {}", roomId, e.getMessage(), e);
        } finally {
            lock.release(roomId);
        }
        if (acted) {
            // 봇이 이어받을 수 있으면 진행 + 다음 턴 타이머 재스케줄.
            botScheduler.scheduleBots(roomId);
            onTurnAdvanced(roomId);
        }
    }

    private void applyAndBroadcast(String roomId, Room room, GameEngine engine, int seat,
                                   GameAction action, GameState state) {
        GameEngine.Result result;
        try {
            result = engine.apply(state, seat, action);
        } catch (GameActionRejectedException rejected) {
            log.warn("Turn timeout action rejected: roomId={} seat={} action={} code={}",
                    roomId, seat, action.getClass().getSimpleName(), rejected.code());
            return;
        }
        engine.saveState(result.newState());
        List<GameEvent> outbound = new ArrayList<>(result.events());
        matchProgress.advance(engine, room, result.newState(), outbound);
        broadcaster.broadcast(roomId, outbound, room.playerIds());
    }

    /**
     * 방이 사라졌을 때 정리. 구현이 in-memory 였을 때는 여기서 안 지우면 맵이
     * 영원히 커졌다(M0 이연 누수). 지금은 Redis 키에 TTL 이 있어 누수가 원천 차단되고,
     * 이 호출은 즉시 회수를 위한 것이다.
     */
    private void cleanup(String roomId) {
        deadlines.cancel(KIND, member(roomId, generations.current(roomId)));
        generations.clear(roomId);
    }

    /** 데드라인 member = `{roomId}#{generation}`. roomId 에 '#' 이 없으므로 lastIndexOf 로 분리 가능. */
    static String member(String roomId, long generation) {
        return roomId + "#" + generation;
    }
}
