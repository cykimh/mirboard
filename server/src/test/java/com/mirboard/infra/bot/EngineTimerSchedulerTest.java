package com.mirboard.infra.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * D-128 — 엔진 타이머. 무장은 턴 진행({@link TurnTimeoutScheduler#onTurnAdvanced})이 턴 데드라인과
 * 같은 세대 번호로 하고, 발화({@link EngineTimerScheduler})는 턴 타임아웃과 같은 가드를 거쳐
 * {@link GameEngine#onTimer} 를 적용한다. 게임 중립이라 엔진은 모의 객체로 충분하다.
 */
class EngineTimerSchedulerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final MatchProgressService matchProgress = mock(MatchProgressService.class);
    private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    private final RoomGeneration generations = mock(RoomGeneration.class);
    private final BotScheduler botScheduler = mock(BotScheduler.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);
    private final GameState next = mock(GameState.class);
    private final GameEvent event = mock(GameEvent.class);

    private static Room room(RoomStatus status, int turnSeconds) {
        return new Room("r1", "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, turnSeconds, 0, Set.of());
    }

    private void engineWithState() {
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
    }

    @Nested
    class Arming {

        private final TurnTimeoutScheduler turnTimeout = new TurnTimeoutScheduler(
                roomService, engines, broadcaster, lock, matchProgress, botScheduler,
                deadlines, generations);

        @Test
        void a_declared_timer_is_armed_with_the_turn_generation_even_without_a_turn_limit() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1500)));

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).schedule(EngineTimerScheduler.KIND, "r1#4", Duration.ofMillis(1500));
            verify(deadlines, never()).schedule(eq(TurnTimeoutScheduler.KIND), anyString(), any());
        }

        @Test
        void without_a_declared_timer_only_the_turn_deadline_is_armed() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 30));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).schedule(TurnTimeoutScheduler.KIND, "r1#4", Duration.ofSeconds(30));
            verify(deadlines, never()).schedule(eq(EngineTimerScheduler.KIND), anyString(), any());
        }

        @Test
        void advancing_drops_the_previous_engine_timer_as_well() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).cancel(TurnTimeoutScheduler.KIND, "r1#3");
            verify(deadlines).cancel(EngineTimerScheduler.KIND, "r1#3");
        }

        @Test
        void a_finished_room_arms_no_engine_timer() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED, 0));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1500)));

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines, never()).schedule(anyString(), anyString(), any());
        }

        @Test
        void cancel_drops_the_engine_timer_too() {
            when(generations.current("r1")).thenReturn(7L);
            when(generations.bump("r1")).thenReturn(8L);

            turnTimeout.cancel("r1");

            verify(deadlines).cancel(EngineTimerScheduler.KIND, "r1#7");
        }

        @Test
        void a_failing_engine_lookup_does_not_block_the_turn_deadline() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 30));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            when(engines.forRoom(any())).thenThrow(new IllegalStateException("boom"));

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).schedule(TurnTimeoutScheduler.KIND, "r1#4", Duration.ofSeconds(30));
        }
    }

    @Nested
    class Firing {

        private final TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);
        private final EngineTimerScheduler scheduler = new EngineTimerScheduler(
                roomService, engines, broadcaster, lock, matchProgress, botScheduler, turnTimeout,
                deadlines, generations);

        @Test
        void a_due_timer_applies_the_engine_transition_and_rearms() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWithState();
            when(engine.onTimer(state)).thenReturn(Optional.of(
                    new GameEngine.Result(next, List.of(event))));

            scheduler.handle("r1#5");

            InOrder order = inOrder(engine, matchProgress, broadcaster, lock, botScheduler, turnTimeout);
            order.verify(engine).saveState(next);
            order.verify(matchProgress).advance(eq(engine), any(), eq(next), eq(List.of(event)));
            order.verify(broadcaster).broadcast("r1", List.of(event), List.of(10L, 20L));
            order.verify(lock).release("r1");
            order.verify(botScheduler).scheduleBots("r1");
            order.verify(turnTimeout).onTurnAdvanced("r1");
        }

        @Test
        void a_stale_generation_touches_nothing() {
            when(generations.current("r1")).thenReturn(6L);

            scheduler.handle("r1#5");

            verify(lock, never()).tryAcquire(anyString());
            verify(engine, never()).onTimer(any());
        }

        @Test
        void a_finished_room_touches_nothing() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED, 0));
            engineWithState();

            scheduler.handle("r1#5");

            verify(lock, never()).tryAcquire(anyString());
            verify(engine, never()).onTimer(any());
        }

        @Test
        void a_busy_room_is_retried_shortly_with_the_same_generation() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(false);

            scheduler.handle("r1#5");

            verify(deadlines).schedule(EngineTimerScheduler.KIND, "r1#5", Duration.ofMillis(200));
            verify(lock, never()).release(anyString());
            verify(engine, never()).onTimer(any());
        }

        @Test
        void a_generation_bumped_while_waiting_for_the_lock_is_dropped_inside_the_lock() {
            when(generations.current("r1")).thenReturn(5L, 6L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWithState();

            scheduler.handle("r1#5");

            verify(engine, never()).onTimer(any());
            verify(lock).release("r1");
            verify(botScheduler, never()).scheduleBots(anyString());
        }

        @Test
        void nothing_due_means_nothing_is_saved_broadcast_or_rearmed() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWithState();
            when(engine.onTimer(state)).thenReturn(Optional.empty());

            scheduler.handle("r1#5");

            verify(engine, never()).saveState(any());
            verify(broadcaster, never()).broadcast(anyString(), any(), any());
            verify(lock).release("r1");
            verify(botScheduler, never()).scheduleBots(anyString());
            verify(turnTimeout, never()).onTurnAdvanced(anyString());
        }

        @Test
        void a_malformed_member_is_ignored() {
            scheduler.handle("no-generation");
            scheduler.handle("r1#not-a-number");

            verify(generations, never()).current(anyString());
        }
    }
}
