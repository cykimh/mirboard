package com.mirboard.infra.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
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

/**
 * D-122 — 끝난 방(IN_GAME 이 아닌 방)에서는 턴 타임아웃·봇 루프가 아무것도 적용하지 않는다.
 * 게임 중립 가드다: 방 상태만 보고, 엔진에 매치 종료를 묻지 않는다(강제 종료는 엔진 상태로는
 * 매치가 안 끝났다).
 *
 * <p>각 가드는 두 지점을 본다 — 락을 잡기 전, 그리고 <b>락을 잡은 뒤 다시</b>. 탈주
 * MATCH_ENDED 는 방 락 안에서 FINISHED 로 만들므로, 락 전에 IN_GAME 을 본 발화가 락을 넘겨받은
 * 뒤 그대로 적용하던 틈이 두 번째 검사로 닫힌다.
 */
class FinishedRoomGuardTest {

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final MatchProgressService matchProgress = mock(MatchProgressService.class);
    private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    private final RoomGeneration generations = mock(RoomGeneration.class);
    private final BotUserRegistry bots = mock(BotUserRegistry.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);
    private final GameAction action = mock(GameAction.class);

    private static Room room(RoomStatus status, List<Integer> botSeats) {
        return new Room("r1", "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, !botSeats.isEmpty(), botSeats, 1000,
                /*turnSeconds*/ 30, 0, Set.of());
    }

    /** 엔진이 "좌석 0 이 행동할 차례, 이 액션을 두라"고 답하는 진행 중 라운드. */
    private void engineWaitsForSeatZero() {
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.isRoundOver(state)).thenReturn(false);
        when(engine.pendingSeat(state)).thenReturn(0);
        when(engine.pendingSeats(state)).thenReturn(List.of(0));
        when(engine.timeoutAction(state, 0)).thenReturn(action);
        when(engine.botAction(eq(state), eq(0), any())).thenReturn(action);
        when(engine.apply(state, 0, action))
                .thenReturn(new GameEngine.Result(state, List.of()));
    }

    @Nested
    class TurnTimeout {

        private final BotScheduler botScheduler = mock(BotScheduler.class);
        private final TurnTimeoutScheduler scheduler = new TurnTimeoutScheduler(
                roomService, engines, broadcaster, lock, matchProgress, botScheduler,
                deadlines, generations);

        @Test
        void a_deadline_firing_in_a_finished_room_applies_nothing() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED, List.of()));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWaitsForSeatZero();

            scheduler.handle("r1#5");

            verify(engine, never()).apply(any(), anyInt(), any());
            verify(broadcaster, never()).broadcast(anyString(), any(), any());
            verify(botScheduler, never()).scheduleBots(anyString());
            verify(deadlines, never()).schedule(anyString(), anyString(), any());
        }

        @Test
        void a_room_that_finished_while_the_deadline_waited_for_the_lock_applies_nothing() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(
                    room(RoomStatus.IN_GAME, List.of()), room(RoomStatus.FINISHED, List.of()));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWaitsForSeatZero();

            scheduler.handle("r1#5");

            verify(engine, never()).apply(any(), anyInt(), any());
            verify(broadcaster, never()).broadcast(anyString(), any(), any());
            verify(lock).release("r1");
        }

        /** 대조군 — 진행 중인 방은 그대로 자동 진행한다(가드가 과하게 막지 않는다). */
        @Test
        void a_deadline_in_an_in_game_room_still_applies_the_timeout_action() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, List.of()));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWaitsForSeatZero();

            scheduler.handle("r1#5");

            verify(engine).apply(state, 0, action);
            verify(botScheduler).scheduleBots("r1");
        }

        @Test
        void turn_advance_in_a_finished_room_cancels_without_rearming() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED, List.of()));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);

            scheduler.onTurnAdvanced("r1");

            verify(deadlines).cancel(TurnTimeoutScheduler.KIND, "r1#3");
            verify(deadlines, never()).schedule(anyString(), anyString(), any(Duration.class));
        }

        @Test
        void cancel_bumps_the_generation_and_drops_the_pending_deadline() {
            when(generations.current("r1")).thenReturn(7L);
            when(generations.bump("r1")).thenReturn(8L);

            scheduler.cancel("r1");

            verify(generations).bump("r1");
            verify(deadlines).cancel(TurnTimeoutScheduler.KIND, "r1#7");
            verify(deadlines, never()).schedule(anyString(), anyString(), any(Duration.class));
        }
    }

    @Nested
    class Bots {

        private final TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);
        private final BotScheduler scheduler = new BotScheduler(roomService, engines, broadcaster,
                lock, matchProgress, bots, turnTimeout, /*seed*/ 1L, /*delay*/ 0L);

        @Test
        void bots_do_not_act_in_a_finished_room() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED, List.of(0)));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWaitsForSeatZero();

            scheduler.scheduleBots("r1");

            verify(roomService, timeout(1_000).atLeastOnce()).getRoom("r1");
            verify(engine, after(300).never()).apply(any(), anyInt(), any());
            verify(broadcaster, never()).broadcast(anyString(), any(), any());
        }

        @Test
        void a_room_that_finished_before_the_bot_got_the_lock_applies_nothing() {
            when(roomService.getRoom("r1")).thenReturn(
                    room(RoomStatus.IN_GAME, List.of(0)), room(RoomStatus.FINISHED, List.of(0)));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWaitsForSeatZero();

            scheduler.scheduleBots("r1");

            verify(lock, timeout(1_000)).release("r1");
            verify(engine, after(300).never()).apply(any(), anyInt(), any());
        }

        /** 대조군 — 진행 중인 방의 봇은 그대로 둔다. */
        @Test
        void bots_still_act_in_an_in_game_room() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, List.of(0)));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWaitsForSeatZero();
            when(engine.isRoundOver(state)).thenReturn(false, true); // 한 수 두고 라운드 끝.

            scheduler.scheduleBots("r1");

            verify(engine, timeout(1_000)).apply(state, 0, action);
        }
    }
}
