package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.infra.scheduling.DeadlineHandler;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
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
 * D-128 — 엔진 타이머 발화. 엔진이 선언한 "시간이 지나면 저절로 일어나는 전이"
 * ({@link GameEngine#timer}/{@link GameEngine#onTimer}, 예: 실시간 외치기 경쟁 창의 봇 누름·만료)를
 * 진행한다.
 *
 * <p>무장은 {@link TurnTimeoutScheduler#onTurnAdvanced} 가 턴 데드라인과 같은 세대 번호로 한다.
 * 여기는 발화만 맡고, 가드는 턴 타임아웃과 같다: 세대 → IN_GAME → 락(실패 시 짧게 재시도) → 락 안
 * 재확인. 적용 단계만 {@code timeoutAction} 대신 {@code onTimer} 이고, 결과는 다른 진행과 같은 경로를
 * 탄다(저장 → {@code matchProgress.advance} → 브로드캐스트 → 봇·타이머 재무장).
 *
 * <p>게임을 모른다 — 무엇이 언제 일어나는지는 엔진이 답한다.
 */
@Component
public class EngineTimerScheduler implements DeadlineHandler {

    private static final Logger log = LoggerFactory.getLogger(EngineTimerScheduler.class);

    /** `deadlines:game` 큐. member 는 턴 데드라인과 같은 `{roomId}#{generation}`. */
    public static final String KIND = "game";
    /** 락 경합 시 재시도 간격 — 턴 타임아웃과 같다. */
    private static final Duration LOCK_RETRY = Duration.ofMillis(200);

    private final RoomService roomService;
    private final GameEngineProvider engines;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final BotScheduler botScheduler;
    private final TurnTimeoutScheduler turnTimeout;
    private final DeadlineQueue deadlines;
    private final RoomGeneration generations;

    public EngineTimerScheduler(RoomService roomService,
                                GameEngineProvider engines,
                                GameEventBroadcaster broadcaster,
                                RoomActionLock lock,
                                MatchProgressService matchProgress,
                                @Lazy BotScheduler botScheduler,
                                @Lazy TurnTimeoutScheduler turnTimeout,
                                DeadlineQueue deadlines,
                                RoomGeneration generations) {
        this.roomService = roomService;
        this.engines = engines;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
        this.deadlines = deadlines;
        this.generations = generations;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** 폴러가 만료된 항목을 넘겨준다. 이 인스턴스가 단독 소유한 상태로 들어온다. */
    @Override
    public void handle(String member) {
        int sep = member.lastIndexOf('#');
        if (sep < 0) {
            log.warn("엔진 타이머 member 형식 오류: {}", member);
            return;
        }
        String roomId = member.substring(0, sep);
        long gen;
        try {
            gen = Long.parseLong(member.substring(sep + 1));
        } catch (NumberFormatException e) {
            log.warn("엔진 타이머 generation 파싱 실패: {}", member);
            return;
        }
        fire(roomId, gen);
    }

    private void fire(String roomId, long capturedGen) {
        // 그 사이 누가 행동했으면 generation 이 올라가 있다 — 이 타이머가 본 상태는 이미 없다.
        if (generations.current(roomId) != capturedGen) return;
        if (inGameRoom(roomId).isEmpty()) return;

        if (!lock.tryAcquire(roomId)) {
            // 다른 액션 처리 중 — 짧게 뒤로 미뤄 재시도 (gen 재확인은 그때).
            deadlines.schedule(KIND, TurnTimeoutScheduler.member(roomId, capturedGen), LOCK_RETRY);
            return;
        }
        boolean advanced = false;
        try {
            // 락 안에서 gen·방 상태 재확인 — 락 대기 중 누가 행동했거나 매치가 끝났을 수 있다.
            if (generations.current(roomId) != capturedGen) return;
            Optional<Room> current = inGameRoom(roomId);
            if (current.isEmpty()) return;
            Room room = current.get();

            GameEngine engine = engines.forRoom(room);
            GameState state = engine.loadState().orElse(null);
            if (state == null) return;

            Optional<GameEngine.Result> result = engine.onTimer(state);
            if (result.isEmpty()) return;

            GameState newState = result.get().newState();
            engine.saveState(newState);
            List<GameEvent> outbound = new ArrayList<>(result.get().events());
            matchProgress.advance(engine, room, newState, outbound);
            broadcaster.broadcast(roomId, outbound, room.playerIds());
            advanced = true;
            log.info("Engine timer fired: roomId={} phase={} events={}",
                    roomId, engine.phaseName(newState), outbound.size());
        } catch (RuntimeException e) {
            log.error("EngineTimerScheduler error in room {}: {}", roomId, e.getMessage(), e);
        } finally {
            lock.release(roomId);
        }
        if (advanced) {
            // 다음 차례가 봇이면 이어받고, 턴·엔진 타이머를 새 상태로 다시 건다.
            botScheduler.scheduleBots(roomId);
            turnTimeout.onTurnAdvanced(roomId);
        }
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
}
