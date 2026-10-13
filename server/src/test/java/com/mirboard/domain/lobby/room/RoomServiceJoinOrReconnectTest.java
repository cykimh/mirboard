package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-133 — 초대 링크가 기대는 {@code join-or-reconnect} 의 입장 갈래(저장소는 모의 — Docker 불필요).
 * <ul>
 *   <li>봇까지 꽉 찬 대기실은 지금처럼 관전이다 — 봇은 사람에게 자리를 비켜 주지 않는다(그 안은 기각). 대기실 초대 버튼의
 *       "링크를 받은 친구는 관전으로 들어와요"가 이 갈래를 전제로 한다.</li>
 *   <li>방을 읽은 뒤 앉기 전에 마지막 자리가 차거나 게임이 시작된 경합({@code ROOM_FULL}·{@code GAME_ALREADY_STARTED})은
 *       관전으로 삼키지 않고 그대로 던진다 — 클라 방 화면이 그 코드로 "자리가 없어요 — 관전할까요?" 패널을 띄운다(결정 A).</li>
 *   <li>같은 사람의 동시 이중 요청(두 탭·개발 StrictMode 의 이중 effect)은 둘 다 "플레이어 아님"을 읽고 입장으로 온다. 늦은 쪽의
 *       {@code ALREADY_IN_ROOM} 은 오류가 아니라 재접속이다(D-74 멱등).</li>
 * </ul>
 */
class RoomServiceJoinOrReconnectTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;
    private static final long FRIEND = 10L;
    private static final List<Long> BOTS = List.of(900L, 901L, 902L);

    private final RoomRepository repository = mock(RoomRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RoomService service = new RoomService(repository, mock(GameRegistry.class), Clock.systemUTC(),
            events, mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    private static Room room(RoomStatus status, Long... players) {
        List<Long> ids = List.of(players);
        List<Integer> botSeats = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (BOTS.contains(ids.get(i))) {
                botSeats.add(i);
            }
        }
        return new Room(ROOM_ID, "방", "ONE_CARD", HOST, status, 4, ids.size(), ids, Set.of(), TeamPolicy.SEQUENTIAL,
                0L, !botSeats.isEmpty(), List.copyOf(botSeats), 1000, 0, 0, Set.of());
    }

    private void stored(Room... states) {
        var stub = when(repository.findById(ROOM_ID));
        for (Room state : states) {
            stub = stub.thenReturn(Optional.of(state));
        }
    }

    @Test
    @DisplayName("봇까지 꽉 찬 대기실(게스트 봇 채우기 방)은 관전 — 봇은 자리를 비켜 주지 않는다")
    void full_waiting_room_with_bots_spectates() {
        Room full = room(RoomStatus.WAITING, HOST, 900L, 901L, 902L);
        stored(full, full);

        JoinOrReconnectResult result = service.joinOrReconnect(ROOM_ID, FRIEND);

        assertThat(result.mode()).isEqualTo(JoinOrReconnectResult.Mode.SPECTATING);
        verify(repository, never()).join(anyString(), anyLong(), anyLong());
        verify(repository).addSpectator(ROOM_ID, FRIEND);
    }

    @Test
    @DisplayName("읽은 뒤 마지막 자리가 찬 경합(ROOM_FULL)은 관전으로 삼키지 않고 그대로 던진다 — 클라가 '관전할까요?' 패널")
    void race_room_full_is_not_swallowed() {
        stored(room(RoomStatus.WAITING, HOST, 2L, 3L));
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new RoomFullException(ROOM_ID));

        assertThatThrownBy(() -> service.joinOrReconnect(ROOM_ID, FRIEND)).isInstanceOf(RoomFullException.class);
        verify(repository, never()).addSpectator(anyString(), anyLong());
    }

    @Test
    @DisplayName("읽은 뒤 게임이 시작된 경합(GAME_ALREADY_STARTED)도 그대로 던진다")
    void race_game_started_is_not_swallowed() {
        stored(room(RoomStatus.WAITING, HOST, 2L, 3L));
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new GameAlreadyStartedException(ROOM_ID));

        assertThatThrownBy(() -> service.joinOrReconnect(ROOM_ID, FRIEND))
                .isInstanceOf(GameAlreadyStartedException.class);
        verify(repository, never()).addSpectator(anyString(), anyLong());
    }

    @Test
    @DisplayName("같은 사람의 동시 이중 요청 — 먼저 간 요청이 앉혔으면 늦은 쪽은 ALREADY_IN_ROOM 대신 재접속(RECONNECTED)")
    void concurrent_duplicate_request_reconnects() {
        Room before = room(RoomStatus.WAITING, HOST, 2L);
        Room after = room(RoomStatus.WAITING, HOST, 2L, FRIEND);
        stored(before, after);
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new AlreadyInRoomException(ROOM_ID));

        JoinOrReconnectResult result = service.joinOrReconnect(ROOM_ID, FRIEND);

        assertThat(result.mode()).isEqualTo(JoinOrReconnectResult.Mode.RECONNECTED);
        assertThat(result.room().playerIds()).containsExactly(HOST, 2L, FRIEND);
        verify(repository, never()).addSpectator(anyString(), anyLong());
    }

    @Test
    @DisplayName("ALREADY_IN_ROOM 인데 다시 읽어도 앉아 있지 않으면(그사이 나감) 재접속으로 꾸미지 않고 그대로 던진다")
    void already_in_room_but_not_seated_rethrows() {
        Room before = room(RoomStatus.WAITING, HOST, 2L);
        stored(before, before);
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new AlreadyInRoomException(ROOM_ID));

        assertThatThrownBy(() -> service.joinOrReconnect(ROOM_ID, FRIEND)).isInstanceOf(AlreadyInRoomException.class);
    }
}
