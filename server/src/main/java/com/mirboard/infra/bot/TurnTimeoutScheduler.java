package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameActionRejectedException;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
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
import java.util.Optional;
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
 * per-room generation 을 증가시키고 새 타이머를 (re)스케줄한다. D-131 — 방 액션 락을 쥔 진행 경로(컨트롤러·봇·두
 * 스케줄러·탈주)는 락을 풀기 전에 부른다 — resync 가 같은 락 안에서 상태와 남은 턴 시간({@link #turnRemaining})을 함께
 * 읽기 때문이다. 락 밖에서 부르는 곳은 매치 시작(라운드 스타터 — 시작 상태 저장 직후)뿐이다. 발화된 task 는
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
 *
 * <p>D-122 — <b>IN_GAME 인 방만</b> 진행한다. 탈주 조기 종료·강제 종료는 방을 FINISHED 로
 * 만들 뿐 직전 액션이 걸어 둔 데드라인은 살아 있었고, 이 클래스는 방 상태를 보지 않아 버려진
 * 라운드를 끝까지 자동 진행했다. 판정은 게임 중립(방 상태)이다 — 강제 종료는 엔진 상태로는
 * 매치가 안 끝났으므로 {@code isMatchOver()} 로는 못 막는다. 끝내는 쪽은 {@link #cancel} 로
 * 데드라인을 지운다.
 *
 * <p>D-128 — 진행 직후 <b>엔진 타이머</b>({@link GameEngine#timer})도 같은 세대 번호로 건다
 * ({@code deadlines:game}, 발화는 {@link EngineTimerScheduler}). 액션·봇·타임아웃·탈주·라운드
 * 시작이 이미 이 메서드를 부르므로 호출 지점은 늘지 않고, 턴 제한을 끈 방에서도 걸린다. 무장마다
 * 상태를 한 번 읽는다(Redis GET) — 타이머가 없는 게임도 같은 비용을 낸다.
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
        long gen = invalidate(roomId);

        // D-122 — 끝난 방에는 다음 턴이 없다(매치를 끝낸 액션 직후의 호출 등). 취소만 한다.
        if (room.status() != RoomStatus.IN_GAME) return;

        armEngineTimer(roomId, room, gen);

        int turnSeconds = room.turnSeconds();
        if (turnSeconds <= 0) return;  // 타이머 끔 — 기존 동작 호환.

        deadlines.schedule(KIND, member(roomId, gen), Duration.ofSeconds(turnSeconds));
    }

    /**
     * D-131 — 지금 턴의 남은 시간: 지금 세대의 턴 데드라인까지(이미 지났으면 0 — 폴러가 곧 꺼낸다). 턴 제한이 꺼졌거나
     * IN_GAME 이 아니거나 지금 세대의 데드라인이 없으면 empty. 이전 세대의 항목은 발화해도 버려지므로 남은 시간이 아니다.
     *
     * <p>호출자(resync)는 방 액션 락 안에서 상태와 함께 읽는다 — 진행 경로가 저장·방송·재무장을 모두 그 락 안에서 끝내므로
     * 락 안에서 읽은 값은 함께 읽은 상태의 턴 것이다. 기다리는 좌석이 있는지는 상태를 아는 호출자가 본다.
     */
    public Optional<Duration> turnRemaining(Room room) {
        if (room.turnSeconds() <= 0 || room.status() != RoomStatus.IN_GAME) {
            return Optional.empty();
        }
        long gen = generations.current(room.roomId());
        return deadlines.remaining(KIND, member(room.roomId(), gen));
    }

    /**
     * D-122 — 걸려 있는 턴 데드라인을 취소한다(재무장 없음). 매치를 액션 경로 밖에서 끝내는
     * 쪽 — 탈주 MATCH_ENDED, 호스트/어드민 강제 종료 — 이 부른다. generation 을 올리므로 이미
     * 폴러가 집어 간 항목도 발화 시점에 버려진다. 발화 쪽에도 방 상태 가드가 있으니 이것은
     * 정리(ZSET 회수)와 이중 방어다.
     */
    public void cancel(String roomId) {
        invalidate(roomId);
    }

    /** generation++ 후 이전 generation 의 데드라인(턴·엔진 타이머)을 지운다. 새 generation 을 반환. */
    private long invalidate(String roomId) {
        long prevGen = generations.current(roomId);
        long gen = generations.bump(roomId);
        deadlines.cancel(KIND, member(roomId, prevGen));
        deadlines.cancel(EngineTimerScheduler.KIND, member(roomId, prevGen));
        return gen;
    }

    /**
     * D-128 — 엔진이 시간 전이를 선언하면 남은 시간 뒤로 엔진 타이머를 건다. 실패해도 턴 타이머는
     * 막지 않는다(로그만) — 엔진 타이머가 없는 게임의 진행이 이 경로 때문에 멈추면 안 된다.
     *
     * <p>D-130 — 실패는 ERROR 다. 타이머가 없으면 경쟁 창이 진행 킥({@link GameProgressKick})이 다시 걸 때까지 닫히지
     * 않는다 — WARN 이던 때는 그렇게 사라진 타이머가 Sentry(ERROR 만)에 보이지 않았다.
     */
    private void armEngineTimer(String roomId, Room room, long gen) {
        try {
            GameEngine engine = engines.forRoom(room);
            engine.loadState()
                    .flatMap(engine::timer)
                    .ifPresent(delay -> deadlines.schedule(
                            EngineTimerScheduler.KIND, member(roomId, gen), delay));
        } catch (RuntimeException e) {
            log.error("Engine timer arm failed: roomId={} err={}", roomId, e.toString(), e);
        }
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
        // D-122 — 끝난 방의 남은 데드라인. 버려진 라운드를 진행하지 않는다.
        if (room.status() != RoomStatus.IN_GAME) return;

        if (!lock.tryAcquire(roomId)) {
            // 다른 액션 처리 중 — 짧게 뒤로 미뤄 재시도 (gen 재확인은 그때).
            deadlines.schedule(KIND, member(roomId, capturedGen), LOCK_RETRY);
            return;
        }
        boolean acted = false;
        try {
            // 락 안에서 gen 재확인 (락 대기 중 누가 행동했을 수 있음).
            if (generations.current(roomId) != capturedGen) return;
            // D-122 — 락 안에서 방 상태도 재확인. 탈주 MATCH_ENDED 는 이 락 안에서 방을
            // FINISHED 로 만든다 — 락 전에 IN_GAME 을 봤어도 지금은 끝났을 수 있다.
            Optional<Room> current = inGameRoom(roomId);
            if (current.isEmpty()) return;
            room = current.get();

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
            // 다음 턴 타이머 재스케줄. D-131 — 락 안에서(resync 가 같은 락 안에서 상태와 남은 턴 시간을 함께 읽는다).
            onTurnAdvanced(roomId);
        } catch (RuntimeException e) {
            log.error("TurnTimeoutScheduler error in room {}: {}", roomId, e.getMessage(), e);
        } finally {
            lock.release(roomId);
        }
        if (acted) {
            // 봇이 이어받을 수 있으면 진행 — 락을 푼 뒤에(쥔 채 걸면 루프가 이 락과 부딪쳐 재시도한다).
            botScheduler.scheduleBots(roomId);
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

    /** 지금 IN_GAME 인 방. 없거나 끝났으면 empty. */
    private Optional<Room> inGameRoom(String roomId) {
        try {
            Room room = roomService.getRoom(roomId);
            return room.status() == RoomStatus.IN_GAME ? Optional.of(room) : Optional.empty();
        } catch (RoomNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * 방이 사라졌을 때 정리. 구현이 in-memory 였을 때는 여기서 안 지우면 맵이
     * 영원히 커졌다(M0 이연 누수). 지금은 Redis 키에 TTL 이 있어 누수가 원천 차단되고,
     * 이 호출은 즉시 회수를 위한 것이다.
     */
    private void cleanup(String roomId) {
        long gen = generations.current(roomId);
        deadlines.cancel(KIND, member(roomId, gen));
        deadlines.cancel(EngineTimerScheduler.KIND, member(roomId, gen));
        generations.clear(roomId);
    }

    /** 데드라인 member = `{roomId}#{generation}`. roomId 에 '#' 이 없으므로 lastIndexOf 로 분리 가능. */
    static String member(String roomId, long generation) {
        return roomId + "#" + generation;
    }
}
