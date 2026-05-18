package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.messaging.DomainEventBus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DesertionServiceTest {

    private final RoomService roomService = mock(RoomService.class);
    private final TichuMatchStateStore matchStateStore = mock(TichuMatchStateStore.class);
    private final DomainEventBus events = mock(DomainEventBus.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final BotUserRegistry bots = mock(BotUserRegistry.class);

    private final DesertionService service = new DesertionService(
            roomService, matchStateStore, events, broadcaster, lock, bots);

    private static Room inGame(List<Long> players) {
        return new Room("r1", "방", "TICHU", players.get(0), RoomStatus.IN_GAME,
                4, players.size(), players, Set.of(), TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, Set.of());
    }

    @Test
    void seat0_deserter_makes_team_B_win_and_publishes_with_deserter() {
        List<Long> players = List.of(10L, 20L, 30L, 40L);
        when(bots.isBot(10L)).thenReturn(false);
        when(lock.tryAcquire("r1")).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(inGame(players));
        when(matchStateStore.load("r1"))
                .thenReturn(Optional.of(TichuMatchState.initial(players, 1000)));

        boolean processed = service.processDesertion("r1", 10L);

        assertThat(processed).isTrue();
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(cap.capture());
        assertThat(cap.getValue()).isInstanceOf(TichuMatchCompleted.class);
        TichuMatchCompleted ev = (TichuMatchCompleted) cap.getValue();
        assertThat(ev.winningTeam()).isEqualTo(Team.B); // seat0 = Team A → 상대 B 승.
        assertThat(ev.deserterUserId()).isEqualTo(10L);
        verify(roomService).markFinished("r1");
        verify(broadcaster).broadcast(any(), any(), any());
        verify(lock).release("r1");
    }

    @Test
    void bot_deserter_is_guarded_no_lock_no_publish() {
        when(bots.isBot(99L)).thenReturn(true);

        boolean processed = service.processDesertion("r1", 99L);

        assertThat(processed).isFalse();
        verify(lock, never()).tryAcquire(any());
        verify(events, never()).publish(any());
    }

    @Test
    void already_finished_room_is_idempotent_noop() {
        when(bots.isBot(10L)).thenReturn(false);
        when(lock.tryAcquire("r1")).thenReturn(true);
        Room finished = new Room("r1", "방", "TICHU", 10L, RoomStatus.FINISHED,
                4, 4, List.of(10L, 20L, 30L, 40L), Set.of(), TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, Set.of());
        when(roomService.getRoom("r1")).thenReturn(finished);

        boolean processed = service.processDesertion("r1", 10L);

        assertThat(processed).isFalse();
        verify(events, never()).publish(any());
        verify(roomService, never()).markFinished(any());
        verify(lock).release("r1"); // 락은 반드시 해제.
    }

    @Test
    void non_participant_is_noop() {
        when(bots.isBot(77L)).thenReturn(false);
        when(lock.tryAcquire("r1")).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(inGame(List.of(10L, 20L, 30L, 40L)));

        boolean processed = service.processDesertion("r1", 77L);

        assertThat(processed).isFalse();
        verify(events, never()).publish(any());
        verify(lock).release("r1");
    }
}
