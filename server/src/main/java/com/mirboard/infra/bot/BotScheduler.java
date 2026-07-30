package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameActionRejectedException;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Phase 9C — 서버 사이드 봇 액션 스케줄러.
 *
 * <p>호출 트리거:
 * <ul>
 *   <li>게임 도메인의 라운드 시작 직후 (티츄: {@code TichuRoundStarter})</li>
 *   <li>{@link com.mirboard.infra.ws.GameStompController} 가 인간 액션 처리 직후</li>
 *   <li>본인이 봇 액션을 처리한 후 재귀 — 다음 봇이 있으면 이어서</li>
 * </ul>
 *
 * <p>흐름:
 * <pre>
 *   scheduleBots(roomId)  // 비동기 실행 — 가상스레드
 *     ↓
 *   lock.tryAcquire(roomId)
 *     ↓
 *   engine = engines.forRoom(room);  state = engine.loadState()
 *     ↓
 *   bot seat = engine.pendingSeats(state) ∩ room.botSeats() 의 첫 좌석
 *     ↓
 *   engine.botAction(state, seat, random) → action
 *     ↓ (null 이면 종료)
 *   engine.apply(state, seat, action) → newState + events
 *     ↓
 *   engine.saveState + matchProgress.advance + broadcaster.broadcast
 *     ↓
 *   lock.release
 *     ↓
 *   self-recurse (남은 봇 액션 있을 때까지)
 * </pre>
 *
 * <p>D-98: 게임을 모른다. "이 좌석이 지금 행동해야 하나"(과거 이 클래스 안의
 * {@code hasPendingAction} switch)와 "무엇을 낼까"(과거 {@code RandomBotPolicy} 직접 호출)를
 * 모두 포트에 물어본다. 시드 재현성은 여기서 보유하는 {@link Random} 이 유지한다.
 *
 * <p>안전망: 1 라운드 내 봇 액션 최대 {@value #MAX_BOT_ACTIONS_PER_ROOM} 회. 초과 시
 * 경고 로그 + 스케줄 중단 (무한 루프 방어).
 */
@Component
public class BotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
    private static final int MAX_BOT_ACTIONS_PER_ROOM = 5000;

    private final RoomService roomService;
    private final GameEngineProvider engines;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final BotUserRegistry bots;
    private final TurnTimeoutScheduler turnTimeout;
    private final ExecutorService executor;
    private final Random random;
    private final long botDelayMillis;

    public BotScheduler(RoomService roomService,
                        GameEngineProvider engines,
                        GameEventBroadcaster broadcaster,
                        RoomActionLock lock,
                        MatchProgressService matchProgress,
                        BotUserRegistry bots,
                        @Lazy TurnTimeoutScheduler turnTimeout,
                        @Value("${mirboard.bot.seed:-1}") long seed,
                        @Value("${mirboard.bot.delay-millis:200}") long botDelayMillis) {
        this.roomService = roomService;
        this.engines = engines;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.bots = bots;
        this.turnTimeout = turnTimeout;
        this.random = seed < 0 ? new SecureRandom() : new Random(seed);
        this.botDelayMillis = botDelayMillis;
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mirboard-bot-", 0).factory());
    }

    /** 비동기 진입점. 호출자는 락 비점유 상태여야 한다. */
    public void scheduleBots(String roomId) {
        executor.execute(() -> runRoom(roomId, 0));
    }

    private void runRoom(String roomId, int iterations) {
        if (iterations >= MAX_BOT_ACTIONS_PER_ROOM) {
            log.warn("Bot loop guard tripped at {} iterations: roomId={}", iterations, roomId);
            return;
        }
        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            // 방이 사라짐 (매치 종료 후 finished + TTL) — 정상 종료.
            return;
        }
        // 솔로 방이 아니면 아무것도 안 함.
        if (room.botSeats().isEmpty()) return;

        // 봇 액션 사이에 약간 딜레이 — 인간 클라가 UI 갱신 따라잡을 시간 + 사람 페이스
        // 흉내. 시뮬레이션 IT 에서는 mirboard.bot.delay-millis=0 으로 끈다.
        if (botDelayMillis > 0) {
            try {
                Thread.sleep(botDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!lock.tryAcquire(roomId)) {
            // 다른 액션 처리 중 — 잠시 후 재시도.
            executor.execute(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                runRoom(roomId, iterations);
            });
            return;
        }
        try {
            GameEngine engine = engines.forRoom(room);
            GameState state = engine.loadState().orElse(null);
            if (state == null) {
                log.warn("Bot loop: state is null, returning. roomId={}", roomId);
                return;
            }
            if (engine.isRoundOver(state)) {
                log.warn("Bot loop: round is already over (matchProgress should have advanced). roomId={} phase={}",
                        roomId, engine.phaseName(state));
                return;
            }

            int botSeat = nextBotSeat(room, engine, state);
            if (botSeat < 0) {
                log.warn("Bot loop: no pending bot action. roomId={} phase={} botSeats={} pending={}",
                        roomId, engine.phaseName(state), room.botSeats(),
                        engine.pendingSeats(state));
                return;
            }

            GameAction action = engine.botAction(state, botSeat, random);
            if (action == null) {
                // 봇 차례인데 합법 액션 0개 — 데드락 위험. 진단 정보 dump.
                log.warn("Bot has no legal action: roomId={} seat={} phase={} state={}",
                        roomId, botSeat, engine.phaseName(state), state);
                return;
            }

            applyAndBroadcast(roomId, room, engine, botSeat, action, state);
        } catch (RuntimeException e) {
            log.error("BotScheduler error in room {}: {}", roomId, e.getMessage(), e);
            return;
        } finally {
            lock.release(roomId);
        }
        // 락 해제 후 재귀 — 다음 봇 있으면 이어서.
        runRoom(roomId, iterations + 1);
    }

    /**
     * 현재 봇이 액션을 취해야 하는 seat. 없으면 -1.
     *
     * <p>봇 좌석 순서로 훑는다 (pending 순서가 아니라) — 여러 좌석이 동시에 대기하는
     * 단계에서 어느 봇이 먼저 움직이는지가 시드 재현성에 걸리기 때문.
     */
    private static int nextBotSeat(Room room, GameEngine engine, GameState state) {
        List<Integer> pending = engine.pendingSeats(state);
        for (int seat : room.botSeats()) {
            if (pending.contains(seat)) return seat;
        }
        return -1;
    }

    private void applyAndBroadcast(String roomId, Room room, GameEngine engine, int seat,
                                   GameAction action, GameState state) {
        GameEngine.Result result;
        try {
            result = engine.apply(state, seat, action);
        } catch (GameActionRejectedException rejected) {
            log.warn("Bot action rejected: roomId={} seat={} action={} code={}",
                    roomId, seat, action.getClass().getSimpleName(), rejected.code());
            return;
        }
        engine.saveState(result.newState());
        List<GameEvent> outbound = new ArrayList<>(result.events());
        matchProgress.advance(engine, room, result.newState(), outbound);
        broadcaster.broadcast(roomId, outbound, room.playerIds());
        // Phase 13D — 봇 액션 후에도 다음 턴 타임아웃 (re)스케줄 (인간 차례면 카운트 시작).
        turnTimeout.onTurnAdvanced(roomId);
        log.debug("Bot action applied: roomId={} seat={} action={} eventsCount={}",
                roomId, seat, action.getClass().getSimpleName(), outbound.size());
    }
}
