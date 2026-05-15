package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.TichuEngine;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.action.TichuActionRejectedException;
import com.mirboard.domain.game.tichu.bot.RandomBotPolicy;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
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
import org.springframework.stereotype.Component;

/**
 * Phase 9C — 서버 사이드 봇 액션 스케줄러.
 *
 * <p>호출 트리거:
 * <ul>
 *   <li>{@link com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter} 가 라운드 시작
 *       (Dealing 으로 전이) 직후</li>
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
 *   state = stateStore.load(roomId)
 *     ↓
 *   bot seat = 첫 봇 seat (있으면)
 *     ↓
 *   policy.choose(state, seat) → action
 *     ↓ (null 이면 종료)
 *   engine.apply(state, seat, action) → newState + events
 *     ↓
 *   stateStore.save + matchProgress.onRoundEnd (필요 시) + broadcaster.broadcast
 *     ↓
 *   lock.release
 *     ↓
 *   self-recurse (남은 봇 액션 있을 때까지)
 * </pre>
 *
 * <p>안전망: 1 라운드 내 봇 액션 최대 {@value #MAX_BOT_ACTIONS_PER_ROOM} 회. 초과 시
 * 경고 로그 + 스케줄 중단 (무한 루프 방어).
 */
@Component
public class BotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
    private static final int MAX_BOT_ACTIONS_PER_ROOM = 5000;

    private final RoomService roomService;
    private final TichuGameStateStore stateStore;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final BotUserRegistry bots;
    private final RandomBotPolicy policy;
    private final ExecutorService executor;
    private final long botDelayMillis;

    public BotScheduler(RoomService roomService,
                        TichuGameStateStore stateStore,
                        GameEventBroadcaster broadcaster,
                        RoomActionLock lock,
                        MatchProgressService matchProgress,
                        BotUserRegistry bots,
                        @Value("${mirboard.bot.seed:-1}") long seed,
                        @Value("${mirboard.bot.delay-millis:200}") long botDelayMillis) {
        this.roomService = roomService;
        this.stateStore = stateStore;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.bots = bots;
        Random random = seed < 0 ? new SecureRandom() : new Random(seed);
        this.policy = new RandomBotPolicy(random);
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
            TichuState state = stateStore.load(roomId).orElse(null);
            if (state == null) {
                log.warn("Bot loop: state is null, returning. roomId={}", roomId);
                return;
            }
            if (state instanceof TichuState.RoundEnd re) {
                log.warn("Bot loop: state is RoundEnd unexpectedly (matchProgress should have advanced). roomId={} A={} B={}",
                        roomId, re.teamAScore(), re.teamBScore());
                return;
            }

            int botSeat = nextBotSeat(room, state);
            if (botSeat < 0) {
                log.warn("Bot loop: no pending bot action. roomId={} phase={} botSeats={} state={}",
                        roomId, state.getClass().getSimpleName(), room.botSeats(), state);
                return;
            }

            TichuAction action = policy.choose(state, botSeat);
            if (action == null) {
                // 봇 차례인데 합법 액션 0개 — 데드락 위험. 진단 정보 dump.
                log.warn("Bot has no legal action: roomId={} seat={} phase={} state={}",
                        roomId, botSeat, state.getClass().getSimpleName(), state);
                return;
            }

            applyAndBroadcast(roomId, room, botSeat, action, state);
        } catch (RuntimeException e) {
            log.error("BotScheduler error in room {}: {}", roomId, e.getMessage(), e);
            return;
        } finally {
            lock.release(roomId);
        }
        // 락 해제 후 재귀 — 다음 봇 있으면 이어서.
        runRoom(roomId, iterations + 1);
    }

    /** 현재 봇이 액션을 취해야 하는 seat. 없으면 -1. */
    private int nextBotSeat(Room room, TichuState state) {
        for (int seat : room.botSeats()) {
            if (hasPendingAction(state, seat)) return seat;
        }
        return -1;
    }

    private static boolean hasPendingAction(TichuState state, int seat) {
        return switch (state) {
            case TichuState.Dealing d -> !d.ready().contains(seat);
            case TichuState.Passing p -> !p.submitted().containsKey(seat);
            case TichuState.Playing pl -> {
                var trick = pl.trick();
                // Dragon trick 양도 보류
                if (trick.currentTop() != null
                        && trick.currentTop().cards().size() == 1
                        && trick.currentTop().cards().get(0).is(
                                com.mirboard.domain.game.tichu.card.Special.DRAGON)
                        && trick.currentTopSeat() == seat) {
                    yield true;
                }
                yield trick.currentTurnSeat() == seat
                        && !pl.players().get(seat).isFinished();
            }
            case TichuState.RoundEnd __ -> false;
        };
    }

    private void applyAndBroadcast(String roomId, Room room, int seat,
                                   TichuAction action, TichuState state) {
        TichuEngine engine = new TichuEngine(new GameContext(roomId, room.playerIds()));
        TichuEngine.Result result;
        try {
            result = engine.apply(state, seat, action);
        } catch (TichuActionRejectedException rejected) {
            log.warn("Bot action rejected: roomId={} seat={} action={} reason={}",
                    roomId, seat, action.getClass().getSimpleName(), rejected.reason());
            return;
        }
        stateStore.save(roomId, result.newState());
        List<TichuEvent> outbound = new ArrayList<>(result.events());
        if (result.newState() instanceof TichuState.RoundEnd ended) {
            matchProgress.onRoundEnd(roomId, room, ended, outbound);
        }
        broadcaster.broadcast(roomId, outbound, room.playerIds());
        log.debug("Bot action applied: roomId={} seat={} action={} eventsCount={}",
                roomId, seat, action.getClass().getSimpleName(), outbound.size());
    }
}
