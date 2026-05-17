package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.TichuEngine;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.action.TichuActionRejectedException;
import com.mirboard.domain.game.tichu.bot.TimeoutActionPolicy;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Phase 13D(#6) — 개인 턴 제한 시간 초과 시 자동으로 안전 액션을 적용해 다음
 * 순서로 넘긴다.
 *
 * <p>구조는 {@link BotScheduler} 와 동일한 lock 공유 패턴. 차이: 가상스레드 즉시
 * 실행이 아니라 {@link ScheduledExecutorService} 지연 실행.
 *
 * <p>매 액션 적용 후 (인간/봇/타임아웃) {@link #onTurnAdvanced(String)} 가 호출되어
 * per-room generation 을 증가시키고 새 타이머를 (re)스케줄한다. 발화된 task 는
 * 캡처한 generation 이 현재와 다르면 abort — 그 사이 누군가 행동했다는 뜻
 * (중복 자동행동 방지). {@link RoomActionLock} 2초 TTL 로 human/bot/timeout 3자
 * 경합을 직렬화.
 *
 * <p>단일 머신 배포 전제 — generation/future 맵이 in-memory. 다중 인스턴스 전환
 * 시 Redis 동기화 필요 (Phase 6D 패턴, 본 범위 외).
 */
@Component
public class TurnTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(TurnTimeoutScheduler.class);

    private final RoomService roomService;
    private final TichuGameStateStore stateStore;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final BotScheduler botScheduler;
    private final ScheduledExecutorService scheduler;

    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public TurnTimeoutScheduler(RoomService roomService,
                                TichuGameStateStore stateStore,
                                GameEventBroadcaster broadcaster,
                                RoomActionLock lock,
                                MatchProgressService matchProgress,
                                @Lazy BotScheduler botScheduler) {
        this.roomService = roomService;
        this.stateStore = stateStore;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.botScheduler = botScheduler;
        this.scheduler = Executors.newScheduledThreadPool(2,
                r -> {
                    Thread t = new Thread(r, "mirboard-turn-timeout");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 턴이 진행됐을 때 (액션 적용 직후) 호출. generation++ 후 turnSeconds>0 이면
     * 타이머 재스케줄. turnSeconds=0 (끔) 이면 기존 타이머만 취소하고 종료.
     */
    public void onTurnAdvanced(String roomId) {
        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            cleanup(roomId);
            return;
        }
        long gen = generations.computeIfAbsent(roomId, k -> new AtomicLong())
                .incrementAndGet();
        cancelFuture(roomId);

        int turnSeconds = room.turnSeconds();
        if (turnSeconds <= 0) return;  // 타이머 끔 — 기존 동작 호환.

        ScheduledFuture<?> f = scheduler.schedule(
                () -> fire(roomId, gen), turnSeconds, TimeUnit.SECONDS);
        futures.put(roomId, f);
    }

    private void fire(String roomId, long capturedGen) {
        AtomicLong g = generations.get(roomId);
        if (g == null || g.get() != capturedGen) return;  // 그 사이 누가 행동 — abort.

        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            cleanup(roomId);
            return;
        }

        if (!lock.tryAcquire(roomId)) {
            // 다른 액션 처리 중 — 짧게 뒤로 미뤄 재시도 (gen 재확인은 그때).
            scheduler.schedule(() -> fire(roomId, capturedGen), 200, TimeUnit.MILLISECONDS);
            return;
        }
        boolean acted = false;
        try {
            // 락 안에서 gen 재확인 (락 대기 중 누가 행동했을 수 있음).
            if (g.get() != capturedGen) return;

            TichuState state = stateStore.load(roomId).orElse(null);
            if (state == null || state instanceof TichuState.RoundEnd) return;

            int seat = pendingSeat(state);
            if (seat < 0) return;

            TichuAction action = TimeoutActionPolicy.choose(state, seat);
            if (action == null) {
                log.warn("Turn timeout: no legal action. roomId={} seat={} phase={}",
                        roomId, seat, state.getClass().getSimpleName());
                return;
            }
            applyAndBroadcast(roomId, room, seat, action, state);
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

    /** 현재 행동을 기다리는 좌석 (Dealing 안-ready / Passing 안-submitted / Playing 차례). */
    private static int pendingSeat(TichuState state) {
        return switch (state) {
            case TichuState.Dealing d -> {
                for (int s = 0; s < 4; s++) if (!d.ready().contains(s)) yield s;
                yield -1;
            }
            case TichuState.Passing p -> {
                for (int s = 0; s < 4; s++) if (!p.submitted().containsKey(s)) yield s;
                yield -1;
            }
            case TichuState.Playing pl -> {
                var trick = pl.trick();
                if (trick.currentTop() != null
                        && trick.currentTop().cards().size() == 1
                        && trick.currentTop().cards().get(0).is(Special.DRAGON)) {
                    yield trick.currentTopSeat();
                }
                int cur = trick.currentTurnSeat();
                yield pl.players().get(cur).isFinished() ? -1 : cur;
            }
            case TichuState.RoundEnd __ -> -1;
        };
    }

    private void applyAndBroadcast(String roomId, Room room, int seat,
                                   TichuAction action, TichuState state) {
        TichuEngine engine = new TichuEngine(new GameContext(roomId, room.playerIds()));
        TichuEngine.Result result;
        try {
            result = engine.apply(state, seat, action);
        } catch (TichuActionRejectedException rejected) {
            log.warn("Turn timeout action rejected: roomId={} seat={} action={} reason={}",
                    roomId, seat, action.getClass().getSimpleName(), rejected.reason());
            return;
        }
        stateStore.save(roomId, result.newState());
        List<TichuEvent> outbound = new ArrayList<>(result.events());
        if (result.newState() instanceof TichuState.RoundEnd ended) {
            matchProgress.onRoundEnd(roomId, room, ended, outbound);
        }
        broadcaster.broadcast(roomId, outbound, room.playerIds());
    }

    private void cancelFuture(String roomId) {
        ScheduledFuture<?> prev = futures.remove(roomId);
        if (prev != null) prev.cancel(false);
    }

    private void cleanup(String roomId) {
        cancelFuture(roomId);
        generations.remove(roomId);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
