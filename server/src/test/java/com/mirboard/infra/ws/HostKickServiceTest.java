package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.GameNotInProgressException;
import com.mirboard.domain.lobby.room.NotHostException;
import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-162 — 방장이 게임 중 끊긴 사람을 내보낸다(Docker 불필요 — 모두 모의). 확인을 통과하면 걸려 있던 탈주 유예를 지우고 유예 만료와
 * 같은 {@link DesertionService#processDesertion} 을 바로 부른다.
 */
class HostKickServiceTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;
    private static final long GONE = 2L;
    private static final long BOT = 9L;

    private final RoomService rooms = mock(RoomService.class);
    private final RoomPresence presence = mock(RoomPresence.class);
    private final DesertionGraceScheduler grace = mock(DesertionGraceScheduler.class);
    private final DesertionService desertion = mock(DesertionService.class);
    private final BotUserRegistry bots = mock(BotUserRegistry.class);
    private final HostKickService service = new HostKickService(rooms, presence, grace, desertion, bots);

    private static Room room(RoomStatus status) {
        return new Room(ROOM_ID, "방", "ONE_CARD", HOST, status, 3, 3, List.of(HOST, GONE, BOT), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(2), 1000, 30, 0, Set.of());
    }

    @BeforeEach
    void setUp() {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room(RoomStatus.IN_GAME));
        when(bots.isBot(BOT)).thenReturn(true);
        when(desertion.processDesertion(ROOM_ID, GONE)).thenReturn(true);
    }

    @Test
    @DisplayName("끊긴 사람을 내보내면 유예를 지우고 바로 탈주 처리한다")
    void kicks_a_disconnected_player_right_away() {
        when(presence.hasLiveSession(GONE, ROOM_ID)).thenReturn(false);

        assertThat(service.kick(ROOM_ID, HOST, GONE)).isTrue();

        verify(grace).cancelIfPending(ROOM_ID, GONE);
        verify(desertion).processDesertion(ROOM_ID, GONE);
    }

    @Test
    @DisplayName("아직 연결돼 있으면 PLAYER_CONNECTED — 탈주 처리하지 않는다")
    void connected_player_is_not_kicked() {
        when(presence.hasLiveSession(GONE, ROOM_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.kick(ROOM_ID, HOST, GONE)).isInstanceOf(PlayerStillConnectedException.class);
        verify(desertion, never()).processDesertion(anyString(), anyLong());
        verify(grace, never()).cancelIfPending(anyString(), anyLong());
    }

    @Test
    @DisplayName("방장이 아니면 NOT_HOST")
    void only_the_host_kicks() {
        assertThatThrownBy(() -> service.kick(ROOM_ID, GONE, HOST)).isInstanceOf(NotHostException.class);
        verify(desertion, never()).processDesertion(anyString(), anyLong());
    }

    @Test
    @DisplayName("진행 중이 아니면 GAME_NOT_IN_PROGRESS")
    void only_in_game() {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room(RoomStatus.WAITING));

        assertThatThrownBy(() -> service.kick(ROOM_ID, HOST, GONE)).isInstanceOf(GameNotInProgressException.class);
    }

    @Test
    @DisplayName("자기 자신·봇·이 판 사람이 아닌 대상은 NOT_IN_ROOM")
    void target_must_be_another_human_player() {
        assertThatThrownBy(() -> service.kick(ROOM_ID, HOST, HOST)).isInstanceOf(NotInRoomException.class);
        assertThatThrownBy(() -> service.kick(ROOM_ID, HOST, BOT)).isInstanceOf(NotInRoomException.class);
        assertThatThrownBy(() -> service.kick(ROOM_ID, HOST, 77L)).isInstanceOf(NotInRoomException.class);
        verify(desertion, never()).processDesertion(anyString(), anyLong());
    }

    @Test
    @DisplayName("이미 탈주한 좌석이면(처리 안 됨) false — 오류는 아니다")
    void already_deserted_is_a_no_op() {
        when(desertion.processDesertion(ROOM_ID, GONE)).thenReturn(false);

        assertThat(service.kick(ROOM_ID, HOST, GONE)).isFalse();
    }
}
