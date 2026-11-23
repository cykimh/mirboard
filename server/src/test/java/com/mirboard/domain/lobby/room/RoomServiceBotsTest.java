package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * D-174·D-290 — 대기실의 봇 앉히기·빼기·사람 내보내기(강퇴)와, 봇이 있는 대기실의 나가기(봇만 남으면 방 없애기·방장은 사람에게).
 * 저장소는 모의라 Docker 가 필요 없다 — 좌석 원자성은 {@code room_bot_add.lua}·{@code room_remove_member.lua} 가 맡고
 * {@code RoomLobbyControlsIT} 가 실제 Redis 로 확인한다.
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

    // ---------- 앉히기 (D-290 — room_bot_add.lua 한 번) ----------

    @Test
    @DisplayName("[봇 추가]는 한 명 — 후보는 봇 전체(이미 앉은 봇은 Lua 가 건너뛴다), 준비는 넣지 않는다")
    void adds_one_bot_atomically() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(room(RoomStatus.WAITING, HOST, 4, 0, HOST, 900L)),
                Optional.of(room(RoomStatus.WAITING, HOST, 4, 0, HOST, 900L, 901L)));
        when(repository.addBots(eq(ROOM_ID), eq(HOST), eq(1), eq(BOT_POOL), anyLong())).thenReturn(1);

        Room after = service.addBots(ROOM_ID, HOST, 1);

        assertThat(after.botSeats()).containsExactly(1, 2);
        verify(repository, never()).join(anyString(), anyLong(), anyLong());
        verify(repository, never()).setReady(anyString(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(events).publishEvent(org.mockito.ArgumentMatchers.any(RoomChangedEvent.class));
    }

    @Test
    @DisplayName("수를 생략하면 정원까지(-1) — 예전 [빈자리 봇으로 채우기]")
    void omitted_count_fills_to_capacity() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, HOST, 4, 0, HOST)));
        when(repository.addBots(eq(ROOM_ID), eq(HOST), eq(-1), eq(BOT_POOL), anyLong())).thenReturn(3);

        service.fillBots(ROOM_ID, HOST);

        verify(repository).addBots(eq(ROOM_ID), eq(HOST), eq(-1), eq(BOT_POOL), anyLong());
    }

    @Test
    @DisplayName("한 명도 못 앉혔으면 — 수를 정했을 때는 ROOM_FULL, 정원까지 채우기는 그대로 둔다")
    void nothing_added() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, HOST, 2, 0, HOST, GUEST)));
        when(repository.addBots(eq(ROOM_ID), eq(HOST), anyInt(), eq(BOT_POOL), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.addBots(ROOM_ID, HOST, 1)).isInstanceOf(RoomFullException.class);
        assertThat(service.fillBots(ROOM_ID, HOST).playerIds()).containsExactly(HOST, GUEST);
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any(RoomChangedEvent.class));
    }

    @Test
    @DisplayName("방장이 아니면 NOT_HOST, 시작된 방이면 GAME_ALREADY_STARTED, 판돈 방이면 STAKED_ROOM_NO_BOTS, 0명은 INVALID_ROOM_SETTING")
    void only_the_host_of_a_waiting_unstaked_room() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, GUEST)));
        assertThatThrownBy(() -> service.addBots(ROOM_ID, GUEST, 1)).isInstanceOf(NotHostException.class);
        assertThatThrownBy(() -> service.addBots(ROOM_ID, HOST, 0)).isInstanceOf(InvalidRoomSettingException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.IN_GAME, HOST, 2, 0, HOST, GUEST)));
        assertThatThrownBy(() -> service.addBots(ROOM_ID, HOST, 1)).isInstanceOf(GameAlreadyStartedException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, HOST, 4, 100, HOST)));
        assertThatThrownBy(() -> service.addBots(ROOM_ID, HOST, 1)).isInstanceOf(StakedRoomNoBotsException.class);

        verify(repository, never()).addBots(anyString(), anyLong(), anyInt(), org.mockito.ArgumentMatchers.anyList(), anyLong());
    }

    @Test
    @DisplayName("확인 뒤 바뀐 방은 Lua 코드가 같은 예외로 — 시작됨(-2)·방장 아님(-3)·판돈(-4)")
    void lua_codes_become_the_same_exceptions() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST)));
        when(repository.addBots(eq(ROOM_ID), eq(HOST), anyInt(), eq(BOT_POOL), anyLong())).thenReturn(-2, -3, -4);

        assertThatThrownBy(() -> service.addBots(ROOM_ID, HOST, 1)).isInstanceOf(GameAlreadyStartedException.class);
        assertThatThrownBy(() -> service.addBots(ROOM_ID, HOST, 1)).isInstanceOf(NotHostException.class);
        assertThatThrownBy(() -> service.addBots(ROOM_ID, HOST, 1)).isInstanceOf(StakedRoomNoBotsException.class);
    }

    // ---------- 빼기 (D-290 — room_remove_member.lua) ----------

    @Test
    @DisplayName("봇 하나만 뺀다 — 재입장 금지는 걸지 않는다")
    void removes_one_bot() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(waiting(HOST, 900L, GUEST, 901L)),
                Optional.of(waiting(HOST, 900L, GUEST)));
        when(repository.removeMember(ROOM_ID, HOST, 901L, 0)).thenReturn(3);

        Room after = service.removeBot(ROOM_ID, HOST, 901L);

        verify(repository).removeMember(ROOM_ID, HOST, 901L, 0);
        assertThat(after.playerIds()).containsExactly(HOST, 900L, GUEST);
    }

    @Test
    @DisplayName("봇 빼기로 사람은 못 뺀다(NOT_IN_ROOM) — 사람은 강퇴로")
    void remove_bot_is_only_for_bots() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, GUEST, 900L)));

        assertThatThrownBy(() -> service.removeBot(ROOM_ID, HOST, GUEST)).isInstanceOf(NotInRoomException.class);
        verify(repository, never()).removeMember(anyString(), anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("봇만 모두 뺀다 — 사람은 그대로")
    void removes_every_bot() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(waiting(HOST, 900L, GUEST, 901L)),
                Optional.of(waiting(HOST, GUEST)));
        when(repository.removeMember(eq(ROOM_ID), eq(HOST), anyLong(), eq(0))).thenReturn(2);

        Room after = service.removeBots(ROOM_ID, HOST);

        verify(repository).removeMember(ROOM_ID, HOST, 900L, 0);
        verify(repository).removeMember(ROOM_ID, HOST, 901L, 0);
        verify(repository, never()).removeMember(ROOM_ID, HOST, GUEST, 0);
        assertThat(after.playerIds()).containsExactly(HOST, GUEST);
    }

    @Test
    @DisplayName("봇 빼기도 방장만, 대기실에서만")
    void removing_bots_is_for_the_host_while_waiting() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, GUEST, 900L)));
        assertThatThrownBy(() -> service.removeBots(ROOM_ID, GUEST)).isInstanceOf(NotHostException.class);
        assertThatThrownBy(() -> service.removeBot(ROOM_ID, GUEST, 900L)).isInstanceOf(NotHostException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.IN_GAME, HOST, 3, 0, HOST, GUEST, 900L)));
        assertThatThrownBy(() -> service.removeBots(ROOM_ID, HOST)).isInstanceOf(GameAlreadyStartedException.class);
        assertThatThrownBy(() -> service.removeBot(ROOM_ID, HOST, 900L)).isInstanceOf(GameAlreadyStartedException.class);

        verify(repository, never()).removeMember(anyString(), anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("확인 뒤 판이 시작됐으면(Lua -2) GAME_ALREADY_STARTED — 시작된 판의 좌석을 당기지 않는다")
    void a_started_room_keeps_its_seats() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, GUEST, 900L)));
        when(repository.removeMember(ROOM_ID, HOST, 900L, 0)).thenReturn(-2);

        assertThatThrownBy(() -> service.removeBot(ROOM_ID, HOST, 900L)).isInstanceOf(GameAlreadyStartedException.class);
        verify(repository, never()).leave(anyString(), anyLong());
    }

    // ---------- 대기실 강퇴 (D-290) ----------

    @Test
    @DisplayName("방장이 사람을 내보낸다 — 재입장 금지 60초, 내보냄 알림이 좌석 메타보다 먼저")
    void the_host_kicks_a_human_from_the_lobby() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(waiting(HOST, GUEST, 900L)),
                Optional.of(waiting(HOST, 900L)));
        when(repository.removeMember(ROOM_ID, HOST, GUEST, RoomService.KICK_REJOIN_BLOCK_SECONDS)).thenReturn(2);

        Room after = service.kickFromLobby(ROOM_ID, HOST, GUEST);

        assertThat(RoomService.KICK_REJOIN_BLOCK_SECONDS).isEqualTo(60);
        assertThat(after.playerIds()).containsExactly(HOST, 900L);
        InOrder order = inOrder(events);
        order.verify(events).publishEvent(new RoomMemberKickedEvent(ROOM_ID, GUEST));
        order.verify(events).publishEvent(org.mockito.ArgumentMatchers.any(RoomChangedEvent.class));
    }

    @Test
    @DisplayName("강퇴는 방장만·대기실에서만, 대상은 다른 사람 참가자만(자신·봇·없는 사람은 NOT_IN_ROOM)")
    void lobby_kick_guards() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, GUEST, 900L)));
        assertThatThrownBy(() -> service.kickFromLobby(ROOM_ID, GUEST, HOST)).isInstanceOf(NotHostException.class);
        assertThatThrownBy(() -> service.kickFromLobby(ROOM_ID, HOST, HOST)).isInstanceOf(NotInRoomException.class);
        assertThatThrownBy(() -> service.kickFromLobby(ROOM_ID, HOST, 900L)).isInstanceOf(NotInRoomException.class);
        assertThatThrownBy(() -> service.kickFromLobby(ROOM_ID, HOST, 77L)).isInstanceOf(NotInRoomException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.IN_GAME, HOST, 3, 0, HOST, GUEST, 900L)));
        assertThatThrownBy(() -> service.kickFromLobby(ROOM_ID, HOST, GUEST)).isInstanceOf(GameAlreadyStartedException.class);

        verify(repository, never()).removeMember(anyString(), anyLong(), anyLong(), anyInt());
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any(RoomMemberKickedEvent.class));
    }

    @Test
    @DisplayName("내보낸 사람은 금지가 풀릴 때까지 관전으로도 못 돌아온다 — KICKED_FROM_ROOM")
    void a_kicked_user_cannot_spectate() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST, 900L)));
        when(repository.isKicked(ROOM_ID, GUEST)).thenReturn(true);

        assertThatThrownBy(() -> service.spectate(ROOM_ID, GUEST)).isInstanceOf(KickedFromRoomException.class);
        verify(repository, never()).addSpectator(anyString(), anyLong());
    }

    @Test
    @DisplayName("강퇴 기록이 있으면 자동 재입장(재동기화)은 KICKED_FROM_ROOM, 직접 입장은 들어오고 기록을 지운다")
    void auto_rejoin_is_refused_after_a_kick() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(waiting(HOST)));
        when(repository.wasKicked(ROOM_ID, GUEST)).thenReturn(true);

        assertThatThrownBy(() -> service.joinOrReconnect(ROOM_ID, GUEST, true)).isInstanceOf(KickedFromRoomException.class);
        verify(repository, never()).join(anyString(), anyLong(), anyLong());
        verify(repository, never()).clearKickLog(anyString(), anyLong());

        service.joinOrReconnect(ROOM_ID, GUEST, false);
        verify(repository).join(eq(ROOM_ID), eq(GUEST), anyLong());
        verify(repository).clearKickLog(ROOM_ID, GUEST);
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
        when(def.capacityFor(org.mockito.ArgumentMatchers.any())).thenCallRealMethod(); // D-176 기본 = maxPlayers
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
