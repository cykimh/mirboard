package com.mirboard.infra.ws;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoomDisconnectHandlerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final DesertionGraceScheduler grace = mock(DesertionGraceScheduler.class);
    private final PlayerPresenceNotifier presence = mock(PlayerPresenceNotifier.class);
    private final RoomDisconnectHandler handler =
            new RoomDisconnectHandler(roomService, grace, presence);

    private static Room room(RoomStatus status, List<Long> players, Set<Long> spectators) {
        return new Room("r1", "방", "TICHU", players.isEmpty() ? 0L : players.get(0),
                status, 4, players.size(), players, spectators, TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, Set.of());
    }

    @Test
    void waiting_player_disconnect_leaves_immediately() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.WAITING, List.of(1L, 2L), Set.of()));

        handler.onDisconnect("r1", 1L);

        verify(roomService).leaveRoom("r1", 1L);
        verifyNoInteractions(grace);
    }

    @Test
    void waiting_spectator_disconnect_stops_spectating() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.WAITING, List.of(1L), Set.of(9L)));

        handler.onDisconnect("r1", 9L);

        verify(roomService).stopSpectating("r1", 9L);
        verify(roomService, never()).leaveRoom("r1", 9L);
    }

    @Test
    void in_game_player_disconnect_schedules_grace() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.IN_GAME, List.of(1L, 2L, 3L, 4L), Set.of()));

        handler.onDisconnect("r1", 3L);

        verify(grace).scheduleGrace("r1", 3L);
        // userId 3L = seat index 2 → 다른 좌석에 끊김 알림.
        verify(presence).disconnected("r1", 2);
        verify(roomService, never()).leaveRoom("r1", 3L);
    }

    @Test
    void reconnect_with_pending_grace_notifies_other_seats() {
        when(grace.cancelIfPending("r1", 3L)).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.IN_GAME, List.of(1L, 2L, 3L, 4L), Set.of()));

        handler.onReconnect("r1", 3L);

        verify(presence).reconnected("r1", 2);
    }

    @Test
    void reconnect_without_pending_grace_is_noop() {
        when(grace.cancelIfPending("r1", 3L)).thenReturn(false);

        handler.onReconnect("r1", 3L);

        verifyNoInteractions(presence);
        verify(roomService, never()).getRoom("r1");
    }

    @Test
    void in_game_spectator_disconnect_stops_spectating_no_grace() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.IN_GAME, List.of(1L, 2L, 3L, 4L), Set.of(9L)));

        handler.onDisconnect("r1", 9L);

        verify(roomService).stopSpectating("r1", 9L);
        verifyNoInteractions(grace);
    }

    @Test
    void finished_room_disconnect_is_noop() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.FINISHED, List.of(1L), Set.of()));

        handler.onDisconnect("r1", 1L);

        verify(roomService, never()).leaveRoom("r1", 1L);
        verifyNoInteractions(grace);
    }

    @Test
    void missing_room_disconnect_is_noop() {
        when(roomService.getRoom("r1")).thenThrow(new RoomNotFoundException("r1"));

        handler.onDisconnect("r1", 1L);

        verify(roomService, never()).leaveRoom("r1", 1L);
        verifyNoInteractions(grace);
    }
}
