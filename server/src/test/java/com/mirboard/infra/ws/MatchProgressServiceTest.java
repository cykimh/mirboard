package com.mirboard.infra.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-153 — 매치가 정상으로 끝나면 사람만의 방은 대기실로 돌아가고(다음 판, 관전자 자동 착석), 봇이 낀 방(봇과 연습)은 FINISHED 로 닫힌다.
 * 대기실로 돌아갈 때 라이브 세션이 없는 플레이어(판 도중 끊긴 채 남은 사람)에게는 대기실 끊김 유예(D-134)를 건다 — 걸지 않으면 유령
 * 좌석이 다음 판에 낀다.
 */
class MatchProgressServiceTest {

    private final RoomService roomService = mock(RoomService.class);
    private final MirboardMetrics metrics = mock(MirboardMetrics.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);
    private final RoomPresence sessions = mock(RoomPresence.class);
    private final WaitingLeaveGraceScheduler waitingGrace = mock(WaitingLeaveGraceScheduler.class);

    private final MatchProgressService service = new MatchProgressService(
            roomService, metrics, sessions, waitingGrace);

    private static Room room(RoomStatus status, List<Long> players, List<Integer> botSeats) {
        return new Room("r1", "방", "ONE_CARD", 1L, status, 4, players.size(),
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L,
                !botSeats.isEmpty(), botSeats, 1000, 0, 0, Set.of());
    }

    private void matchEnds() {
        when(engine.advance(any(), any())).thenReturn(new GameEngine.Advance(true, true));
    }

    @Test
    void human_only_match_goes_back_to_waiting() {
        matchEnds();
        when(roomService.returnToWaiting("r1")).thenReturn(room(RoomStatus.WAITING, List.of(1L, 2L, 3L), List.of()));
        when(sessions.hasLiveSession(anyLong(), eq("r1"))).thenReturn(true);

        service.advance(engine, room(RoomStatus.IN_GAME, List.of(1L, 2L), List.of()), state, new ArrayList<>());

        verify(roomService).returnToWaiting("r1");
        verify(roomService, never()).markFinished(any());
        verify(waitingGrace, never()).scheduleGrace(any(), anyLong());
    }

    @Test
    void players_left_disconnected_get_the_waiting_grace() {
        matchEnds();
        when(roomService.returnToWaiting("r1")).thenReturn(room(RoomStatus.WAITING, List.of(1L, 2L, 3L), List.of()));
        when(sessions.hasLiveSession(1L, "r1")).thenReturn(true);
        when(sessions.hasLiveSession(2L, "r1")).thenReturn(false);
        when(sessions.hasLiveSession(3L, "r1")).thenReturn(true);

        service.advance(engine, room(RoomStatus.IN_GAME, List.of(1L, 2L), List.of()), state, new ArrayList<>());

        verify(waitingGrace).scheduleGrace("r1", 2L);
        verify(waitingGrace, never()).scheduleGrace("r1", 1L);
        verify(waitingGrace, never()).scheduleGrace("r1", 3L);
    }

    @Test
    void bot_match_finishes_the_room() {
        matchEnds();

        service.advance(engine, room(RoomStatus.IN_GAME, List.of(1L, 900L), List.of(1)), state, new ArrayList<>());

        verify(roomService).markFinished("r1");
        verify(roomService, never()).returnToWaiting(any());
    }

    @Test
    void a_failure_going_back_to_waiting_does_not_escape() {
        matchEnds();
        when(roomService.returnToWaiting("r1")).thenThrow(new RuntimeException("redis down"));

        service.advance(engine, room(RoomStatus.IN_GAME, List.of(1L, 2L), List.of()), state, new ArrayList<>());

        verify(waitingGrace, never()).scheduleGrace(any(), anyLong());
    }

    @Test
    void a_round_that_does_not_end_the_match_never_touches_the_room() {
        when(engine.advance(any(), any())).thenReturn(new GameEngine.Advance(true, false));

        service.advance(engine, room(RoomStatus.IN_GAME, List.of(1L, 2L), List.of()), state, new ArrayList<>());

        verify(roomService, never()).markFinished(any());
        verify(roomService, never()).returnToWaiting(any());
    }
}
