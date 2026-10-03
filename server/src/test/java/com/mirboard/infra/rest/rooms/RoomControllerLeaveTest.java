package com.mirboard.infra.rest.rooms;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.RoomPresence;
import com.mirboard.infra.ws.RoomSeq;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-122 — '나가기'의 좌석 판단. 게임이 시작된 방에서 일반 leave 로 넘기면 {@code room_leave.lua}
 * 의 IN_GAME 분기가 {@code LREM} 으로 좌석 목록을 당긴다. 그러면 라이브 STOMP 의 좌석 판정
 * ({@code indexOf})과 비공개 이벤트 라우팅({@code playerIds.get(seat)})이 남의 좌석을 가리켜
 * 손패가 다른 사람에게 간다(State Hiding).
 *
 * <p>그래서 IN_GAME 참가자의 탈주가 처리되지 않았을 때(이미 탈주한 좌석의 재요청·락 획득
 * 실패) 일반 leave 로 넘기는 것은 <b>매치가 이미 끝난 경우</b>(티츄 리매치 대기, D-82 — 별건)와
 * 그 사이 방이 IN_GAME 을 벗어난 경우(FINISHED 는 lua 가 좌석을 고정)뿐이다. 판정은 게임
 * 중립(엔진 포트의 {@code isMatchOver()})이다.
 */
class RoomControllerLeaveTest {

    private static final long ME = 10L;

    private final RoomService rooms = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final DesertionService desertion = mock(DesertionService.class);
    private final GameEngine engine = mock(GameEngine.class);

    private final RoomController controller = new RoomController(
            rooms, engines, mock(RoomSeq.class), desertion, mock(RoomPresence.class),
            mock(RoomChipStore.class), mock(TurnTimeoutScheduler.class));

    private final AuthPrincipal me = new AuthPrincipal(ME, "me");

    private static Room room(RoomStatus status) {
        List<Long> players = List.of(ME, 20L, 30L);
        return new Room("r1", "방", "SKULL_KING", ME, status, 3, players.size(), players,
                Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    @Test
    void an_accepted_desertion_is_the_whole_leave() {
        when(rooms.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
        when(desertion.processDesertion("r1", ME)).thenReturn(true);

        controller.leave("r1", me);

        verify(rooms, never()).leaveRoom(anyString(), anyLong());
    }

    /**
     * 이미 탈주한 좌석의 재요청(더블클릭, 유예 탈주 뒤 재접속해 '나가기')이나 락 획득 실패로
     * 탈주가 처리되지 않았고, 매치는 계속된다 — 좌석을 그대로 둔다(아무것도 안 한다).
     */
    @Test
    void an_unprocessed_desertion_in_a_live_match_keeps_the_seat() {
        when(rooms.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
        when(desertion.processDesertion("r1", ME)).thenReturn(false);
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.isMatchOver()).thenReturn(false);

        controller.leave("r1", me);

        verify(rooms, never()).leaveRoom(anyString(), anyLong());
    }

    /** 매치가 끝나 리매치를 기다리는 방(티츄 사람만, D-82)은 예전처럼 일반 leave — 별건. */
    @Test
    void a_match_that_is_already_over_falls_back_to_a_plain_leave() {
        when(rooms.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
        when(desertion.processDesertion("r1", ME)).thenReturn(false);
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.isMatchOver()).thenReturn(true);

        controller.leave("r1", me);

        verify(rooms).leaveRoom("r1", ME);
    }

    /**
     * 탈주 처리를 기다리는 사이 다른 탈주가 매치를 끝내 방이 FINISHED 가 됐다 — 일반 leave 로
     * 넘기고, FINISHED 분기(lua)가 좌석을 고정한다.
     */
    @Test
    void a_room_that_finished_meanwhile_falls_back_to_a_plain_leave() {
        when(rooms.getRoom("r1"))
                .thenReturn(room(RoomStatus.IN_GAME))
                .thenReturn(room(RoomStatus.FINISHED));
        when(desertion.processDesertion("r1", ME)).thenReturn(false);
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.isMatchOver()).thenReturn(false);

        controller.leave("r1", me);

        verify(rooms).leaveRoom("r1", ME);
    }

    @Test
    void finished_and_waiting_rooms_take_the_plain_leave_without_desertion() {
        when(rooms.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED));
        controller.leave("r1", me);

        when(rooms.getRoom("r1")).thenReturn(room(RoomStatus.WAITING));
        controller.leave("r1", me);

        verify(desertion, never()).processDesertion(anyString(), anyLong());
        verify(rooms, org.mockito.Mockito.times(2)).leaveRoom("r1", ME);
    }
}
