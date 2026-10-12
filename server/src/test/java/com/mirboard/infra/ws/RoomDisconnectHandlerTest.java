package com.mirboard.infra.ws;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.room.GameAlreadyStartedException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RoomDisconnectHandlerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final DesertionGraceScheduler grace = mock(DesertionGraceScheduler.class);
    private final PlayerPresenceNotifier presence = mock(PlayerPresenceNotifier.class);
    private final RoomPresence sessions = mock(RoomPresence.class);
    private final WaitingLeaveGraceScheduler waitingGrace = mock(WaitingLeaveGraceScheduler.class);
    private final RoomDisconnectHandler handler =
            new RoomDisconnectHandler(roomService, grace, presence, sessions, waitingGrace);

    private static Room room(RoomStatus status, List<Long> players, Set<Long> spectators) {
        return new Room("r1", "방", "TICHU", players.isEmpty() ? 0L : players.get(0),
                status, 4, players.size(), players, spectators, TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    /**
     * D-134 — 휴대폰 방장이 초대 링크를 카톡으로 보내고 기다리는 사이(백그라운드 10~15초면 소켓이 죽는다 — D-79) 혼자 만든
     * 방이 사라지지 않게, 대기실 끊김은 바로 내보내지 않는다. 그 사람의 준비를 먼저 풀어(그 사람 없이 시작되지 않게) 유예를 건다.
     */
    @Test
    @DisplayName("대기실 플레이어가 끊기면 바로 내보내지 않고 준비를 푼 뒤 유예를 건다")
    void waiting_player_disconnect_unreadies_then_schedules_grace_instead_of_leaving() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.WAITING, List.of(1L, 2L), Set.of()));

        handler.onDisconnect("r1", 1L);

        InOrder order = inOrder(roomService, waitingGrace);
        order.verify(roomService).setReady("r1", 1L, false);
        order.verify(waitingGrace).scheduleGrace("r1", 1L);
        verify(roomService, never()).leaveRoom(anyString(), anyLong());
        // 게임 탈주 유예·끊김 배지는 IN_GAME 전용이다.
        verifyNoInteractions(grace, presence);
    }

    /**
     * D-134(FR-2) — 같은 방을 두 탭으로 열었다가 하나를 닫았다(또는 모바일 재접속에서 새 소켓의 구독이 옛 소켓의 늦은 close 보다
     * 먼저 처리됐다). 남은 세션이 있으니 아직 접속 중이다 — IN_GAME 갈래와 같은 가드.
     */
    @Test
    @DisplayName("대기실 끊김인데 같은 방 라이브 세션이 남아 있으면 아무것도 하지 않는다(두 탭 중 하나 닫기)")
    void waiting_disconnect_with_live_session_in_same_room_does_nothing() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.WAITING, List.of(1L, 2L), Set.of()));
        when(sessions.hasLiveSession(1L, "r1")).thenReturn(true);

        handler.onDisconnect("r1", 1L);

        verify(roomService, never()).leaveRoom(anyString(), anyLong());
        verify(roomService, never()).setReady(anyString(), anyLong(), anyBoolean());
        verifyNoInteractions(waitingGrace, grace, presence);
    }

    @Test
    @DisplayName("준비 해제가 실패해도(경합) 유예는 건다 — 만료가 상태를 다시 본다")
    void waiting_unready_failure_still_schedules_grace() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.WAITING, List.of(1L, 2L), Set.of()));
        // getRoom 과 setReady 사이에 게임이 시작됐다(경합) — 예외가 끊김 정리 밖으로 새지 않는다.
        doThrow(new GameAlreadyStartedException("r1")).when(roomService).setReady("r1", 1L, false);

        handler.onDisconnect("r1", 1L);

        verify(waitingGrace).scheduleGrace("r1", 1L);
        verify(roomService, never()).leaveRoom(anyString(), anyLong());
    }

    @Test
    @DisplayName("대기실 관전자가 끊기면 지금처럼 바로 관전을 멈춘다")
    void waiting_spectator_disconnect_stops_spectating() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.WAITING, List.of(1L), Set.of(9L)));

        handler.onDisconnect("r1", 9L);

        verify(roomService).stopSpectating("r1", 9L);
        verify(roomService, never()).leaveRoom("r1", 9L);
        verify(roomService, never()).setReady(anyString(), anyLong(), anyBoolean());
        verifyNoInteractions(waitingGrace);
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
        // D-134 — 게임 중 끊김은 대기실 유예·준비 해제와 무관하다.
        verifyNoInteractions(waitingGrace);
        verify(roomService, never()).setReady(anyString(), anyLong(), anyBoolean());
    }

    @Test
    void in_game_disconnect_with_live_session_skips_grace_and_notify() {
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.IN_GAME, List.of(1L, 2L, 3L, 4L), Set.of()));
        // 모바일 재접속: 새 세션이 먼저 등록된 상태에서 옛 소켓 close 가 도착.
        when(sessions.hasLiveSession(3L, "r1")).thenReturn(true);

        handler.onDisconnect("r1", 3L);

        verify(grace, never()).scheduleGrace("r1", 3L);
        verify(presence, never()).disconnected("r1", 2);
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

    /**
     * D-134 — 대기실 유예 중에 방 토픽을 다시 구독했다(돌아왔다) — 대기실 유예를 취소한다. RECONNECTED 알림은 게임판 배지용이라
     * 보내지 않고, 풀었던 준비도 되돌리지 않는다(돌아온 사람이 다시 누른다).
     */
    @Test
    @DisplayName("다시 구독하면 대기실 유예를 취소한다 — RECONNECTED 알림·준비 복원은 없다")
    void reconnect_cancels_waiting_grace_without_notify() {
        when(waitingGrace.cancelIfPending("r1", 1L)).thenReturn(true);
        when(grace.cancelIfPending("r1", 1L)).thenReturn(false);

        handler.onReconnect("r1", 1L);

        verify(waitingGrace).cancelIfPending("r1", 1L);
        verifyNoInteractions(presence);
        verify(roomService, never()).setReady(anyString(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("다시 구독하면 게임 탈주 유예도 지금처럼 취소하고 알린다")
    void reconnect_cancels_both_and_still_notifies_in_game() {
        when(grace.cancelIfPending("r1", 3L)).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(
                room(RoomStatus.IN_GAME, List.of(1L, 2L, 3L, 4L), Set.of()));

        handler.onReconnect("r1", 3L);

        verify(waitingGrace).cancelIfPending("r1", 3L);
        verify(presence).reconnected("r1", 2);
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
        verifyNoInteractions(grace, waitingGrace);
    }

    @Test
    void missing_room_disconnect_is_noop() {
        when(roomService.getRoom("r1")).thenThrow(new RoomNotFoundException("r1"));

        handler.onDisconnect("r1", 1L);

        verify(roomService, never()).leaveRoom("r1", 1L);
        verifyNoInteractions(grace, waitingGrace);
    }
}
