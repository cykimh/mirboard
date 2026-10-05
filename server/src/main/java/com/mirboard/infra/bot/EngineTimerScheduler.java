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
 * <p><b>마지막 방어선은 락 안의 {@code timer} 재확인이다(D-128).</b> 세대 번호는 대부분의 낡은 발화를
 * 거르지만, 진행 경로(컨트롤러·매치가 이어지는 탈주·두 스케줄러)는 락을 푼 <em>뒤에</em> 세대를 올리므로 그 틈이 있다 — 틈에
 * 만기된 옛 타이머는 락 앞뒤의 세대 검사를 모두 통과해 이미 넘어간 상태를 만난다. 그래서 락 안에서 상태를 읽은 뒤
 * {@code timer(state)} 를 다시 묻는다: 비어 있으면 낡은 발화라 멈추고, 아직 남았으면 같은 세대로 그 시간 뒤에
 * 다시 걸고 멈추며, 0 이하일 때만 {@code onTimer} 를 적용한다. (턴 타임아웃에도 같은 틈이 있다 —
 * 후속 과제로 남겼다(D-128).)
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

            // 세대 검사만으로는 락 해제~세대 상승 틈에 만기된 옛 타이머를 못 거른다 — 지금 상태가 정말 만기인지
            // 락 안에서 다시 묻는다(이 틈에 전이가 한 번 이상 끼었을 수 있다).
            Optional<Duration> left = engine.timer(state);
            if (left.isEmpty()) {
                // 이 상태에는 타이머가 없다 — 상태가 넘어간 뒤의 낡은 발화. 새 상태의 타이머는 그걸 만든 쪽이 건다.
                log.debug("Engine timer stale, state declares none: roomId={} gen={}", roomId, capturedGen);
                return;
            }
            if (left.get().isPositive()) {
                // 아직 만기가 아니다 — 같은 세대로 남은 시간 뒤에 다시 건다(저장·브로드캐스트·재무장 없음).
                // 세대가 그 사이 오르면 다음 발화가 세대 검사에서 버려진다.
                deadlines.schedule(KIND, TurnTimeoutScheduler.member(roomId, capturedGen), left.get());
                log.debug("Engine timer not due yet, rearmed: roomId={} gen={} left={}",
                        roomId, capturedGen, left.get());
                return;
            }

            Optional<GameEngine.Result> result = engine.onTimer(state);
            if (result.isEmpty()) {
                // 만기라고 답했는데 전이가 없다 — 포트 계약 위반. 타이머는 이미 팝돼 사라지므로 조용히 두지 않는다.
                log.warn("Engine timer due but onTimer returned nothing: roomId={} gen={} phase={}",
                        roomId, capturedGen, engine.phaseName(state));
                return;
            }

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
