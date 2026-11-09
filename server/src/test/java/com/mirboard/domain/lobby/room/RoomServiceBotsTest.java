package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-174 — 대기실의 [빈자리 봇으로 채우기]·[봇 빼기]와, 봇이 있는 대기실의 나가기(봇만 남으면 방 없애기·방장은 사람에게). 저장소는 모의라
 * Docker 가 필요 없다 — 좌석 원자성은 기존 {@code room_join.lua}·{@code room_leave.lua} 가 맡는다.
 */
class RoomServiceBotsTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final List<Long> BOT_POOL = List.of(900L, 901L, 902L, 903L, 904L, 905L, 906L, 907L);

    private final RoomRepository repository = mock(RoomRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BotUserRegistry bots = mock(BotUserRegistry.class);
    private final RoomService service = new RoomService(repository, mock(GameRegistry.class), Clock.systemUTC(),
            events, mock(MirboardMetrics.class), bots);

    @BeforeEach
    void botPool() {
        when(bots.getBotIds()).thenReturn(BOT_POOL);
        when(bots.isBot(anyLong())).thenAnswer(inv -> BOT_POOL.contains((Long) inv.getArgument(0)));
    }

    private Room room(RoomStatus status, long host, int capacity, int stake, Long... players) {
        List<Long> ids = List.of(players);
        List<Integer> botSeats = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (BOT_POOL.contains(ids.get(i))) {
                botSeats.add(i);
            }
        }
        return new Room(ROOM_ID, "방", "ONE_CARD", host, status, capacity, ids.size(), ids, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, botSeats, 1000, 0, stake, Set.of());
    }

    private Room waiting(Long... players) {
        return room(RoomStatus.WAITING, HOST, 6, 0, players);
    }

    // ---------- 채우기 ----------

    @Test
    @DisplayName("빈자리만큼 방에 없는 봇을 앉히고 준비는 넣지 않는다")
    void fills_empty_seats_with_bots_not_already_in_the_room() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(room(RoomStatus.WAITING, HOST, 4, 0, HOST, 900L)),
                Optional.of(room(RoomStatus.WAITING, HOST, 4, 0, HOST, 900L, 901L, 902L)));

        Room after = service.fillBots(ROOM_ID, HOST);

        verify(repository).join(eq(ROOM_ID), eq(901L), anyLong());
        verify(repository).join(eq(ROOM_ID), eq(902L), anyLong());
        verify(repository, never()).join(eq(ROOM_ID), eq(900L), anyLong());
        verify(repository, never()).setReady(anyString(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(after.botSeats()).containsExactly(1, 2, 3);
        verify(events).publishEvent(org.mockito.ArgumentMatchers.any(RoomChangedEvent.class));
    }

    @Test
    @DisplayName("채우는 도중 사람이 마지막 자리에 먼저 앉으면(ROOM_FULL) 거기서 멈춘다 — 오류가 아니다")
    void stops_when_a_human_takes_the_last_seat_first() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, HOST, 3, 0, HOST)));
        when(repository.join(eq(ROOM_ID), eq(901L), anyLong())).thenThrow(new RoomFullException(ROOM_ID));

        service.fillBots(ROOM_ID, HOST);

        verify(repository).join(eq(ROOM_ID), eq(900L), anyLong());
        verify(repository).join(eq(ROOM_ID), eq(901L), anyLong());
        verify(repository, never()).join(eq(ROOM_ID), eq(902L), anyLong());
    }

    @Test
    @DisplayName("빈자리가 없으면 아무것도 하지 않는다")
    void a_full_room_is_left_alone() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, HOST, 2, 0, HOST, GUEST)));

        service.fillBots(ROOM_ID, HOST);

        verify(repository, never()).join(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("방장이 아니면 NOT_HOST, 시작된 방이면 GAME_ALREADY_STARTED, 판돈 방이면 STAKED_ROOM_NO_BOTS")
    void only_the_host_of_a_waiting_unstaked_room() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, GUEST)));
        assertThatThrownBy(() -> service.fillBots(ROOM_ID, GUEST)).isInstanceOf(NotHostException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.IN_GAME, HOST, 2, 0, HOST, GUEST)));
        assertThatThrownBy(() -> service.fillBots(ROOM_ID, HOST)).isInstanceOf(GameAlreadyStartedException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, HOST, 4, 100, HOST)));
        assertThatThrownBy(() -> service.fillBots(ROOM_ID, HOST)).isInstanceOf(StakedRoomNoBotsException.class);

        verify(repository, never()).join(anyString(), anyLong(), anyLong());
    }

    // ---------- 빼기 ----------

    @Test
    @DisplayName("봇만 모두 뺀다 — 사람은 그대로")
    void removes_every_bot() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(waiting(HOST, 900L, GUEST, 901L)),
                Optional.of(waiting(HOST, GUEST)));

        Room after = service.removeBots(ROOM_ID, HOST);

        verify(repository).leave(ROOM_ID, 900L);
        verify(repository).leave(ROOM_ID, 901L);
        verify(repository, never()).leave(ROOM_ID, GUEST);
        assertThat(after.playerIds()).containsExactly(HOST, GUEST);
    }

    @Test
    @DisplayName("봇 빼기도 방장만, 대기실에서만")
    void removing_bots_is_for_the_host_while_waiting() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, GUEST, 900L)));
        assertThatThrownBy(() -> service.removeBots(ROOM_ID, GUEST)).isInstanceOf(NotHostException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.IN_GAME, HOST, 3, 0, HOST, GUEST, 900L)));
        assertThatThrownBy(() -> service.removeBots(ROOM_ID, HOST)).isInstanceOf(GameAlreadyStartedException.class);

        verify(repository, never()).leave(anyString(), anyLong());
    }

    // ---------- 나가기 ----------

    @Test
    @DisplayName("사람이 나가 봇만 남으면 봇을 모두 빼 방을 없앤다")
    void a_room_left_with_only_bots_is_removed() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(room(RoomStatus.WAITING, 900L, 6, 0, 900L, 901L)),
                Optional.empty());

        service.leaveRoom(ROOM_ID, HOST);

        InOrder order = inOrder(repository);
        order.verify(repository).leave(ROOM_ID, HOST);
        order.verify(repository).leave(ROOM_ID, 900L);
        order.verify(repository).leave(ROOM_ID, 901L);
        verify(events).publishEvent(RoomChangedEvent.destroyed(ROOM_ID));
    }

    @Test
    @DisplayName("방장이 나가 봇이 방장이 되면 남은 사람에게 넘긴다")
    void the_host_passes_to_a_human_not_a_bot() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(room(RoomStatus.WAITING, 900L, 6, 0, 900L, GUEST, 901L)),
                Optional.of(room(RoomStatus.WAITING, GUEST, 6, 0, 900L, GUEST, 901L)));

        service.leaveRoom(ROOM_ID, HOST);

        verify(repository).setHost(ROOM_ID, GUEST);
        verify(repository, never()).leave(ROOM_ID, 900L);
    }

    @Test
    @DisplayName("사람만의 방 나가기는 예전 그대로 — 방장 넘김·봇 빼기를 하지 않는다")
    void a_human_only_room_leave_is_unchanged() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, GUEST, 6, 0, GUEST)));

        service.leaveRoom(ROOM_ID, HOST);

        verify(repository).leave(ROOM_ID, HOST);
        verify(repository, never()).setHost(anyString(), anyLong());
        verify(repository, never()).leave(ROOM_ID, GUEST);
    }

    @Test
    @DisplayName("대기실로 돌아온 방의 방장이 봇이면 사람에게 넘긴다")
    void returning_to_waiting_hands_a_bot_host_to_a_human() {
        com.mirboard.domain.game.core.GameDefinition def = mock(com.mirboard.domain.game.core.GameDefinition.class);
        when(def.maxPlayers()).thenReturn(6);
        RoomService withGames = gamesWith(def);
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(room(RoomStatus.IN_GAME, 900L, 3, 0, 900L, GUEST, 901L)),
                Optional.of(room(RoomStatus.WAITING, 900L, 6, 0, 900L, GUEST, 901L)),
                Optional.of(room(RoomStatus.WAITING, GUEST, 6, 0, 900L, GUEST, 901L)));
        when(repository.returnToWaiting(eq(ROOM_ID), eq(6), anyLong())).thenReturn(0);

        Room after = withGames.returnToWaiting(ROOM_ID);

        verify(repository).setHost(ROOM_ID, GUEST);
        assertThat(after.hostId()).isEqualTo(GUEST);
    }

    private RoomService gamesWith(com.mirboard.domain.game.core.GameDefinition def) {
        GameRegistry games = mock(GameRegistry.class);
        when(games.require("ONE_CARD")).thenReturn(def);
        return new RoomService(repository, games, Clock.systemUTC(), events, mock(MirboardMetrics.class), bots);
    }
}
