package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-290 — 대기실에서 방장이 하는 세 가지를 실제 Redis(Lua)로 확인한다: 봇 하나씩 넣고 빼기({@code room_bot_add.lua}·
 * {@code room_remove_member.lua}), 사람 내보내기와 재입장 금지, 정원 조절과 그 정원이 입장·관전 판정·다음 판에 닿는 것. 그리고
 * "초대 링크로 사람이 들어오면 봇이 같이 들어온다"의 재현 — 봇과 하는 판에 링크로 온 친구가 다음 판에 봇 자리를 받는다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-lobby-controls-test-secret-must-be-32-bytes"
})
class RoomLobbyControlsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    private static final long HOST = 5001L;
    private static final long FRIEND = 5002L;
    private static final long FRIEND_2 = 5003L;

    @Autowired
    RoomService roomService;

    @Autowired
    BotUserRegistry bots;

    @Autowired
    StringRedisTemplate redis;

    /** 원카드(2~6인) 대기실 — 정원은 화면이 만들 때처럼 게임 최대(6). */
    private Room oneCardRoom() {
        return roomService.createRoom(HOST, "lobby-controls", "ONE_CARD", TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, 0, 6);
    }

    private long botCount(Room room) {
        return room.playerIds().stream().filter(bots::isBot).count();
    }

    // ---------- 봇 하나씩 ----------

    @Test
    @DisplayName("봇을 한 명씩 앉히고 한 명씩 뺀다 — 사람 좌석 순서는 그대로, 봇은 준비 표시가 없다")
    void bots_are_added_and_removed_one_at_a_time() {
        Room room = oneCardRoom();
        String id = room.roomId();

        Room one = roomService.addBots(id, HOST, 1);
        assertThat(one.playerIds()).hasSize(2);
        assertThat(one.botSeats()).containsExactly(1);

        roomService.joinRoom(id, FRIEND);
        Room three = roomService.addBots(id, HOST, 2);
        assertThat(three.playerIds()).hasSize(5);
        assertThat(three.botSeats()).containsExactly(1, 3, 4);
        assertThat(three.readyUserIds()).isEmpty();
        // 서로 다른 봇이다.
        assertThat(three.playerIds()).doesNotHaveDuplicates();

        long firstBot = three.playerIds().get(1);
        Room after = roomService.removeBot(id, HOST, firstBot);
        assertThat(after.playerIds()).hasSize(4).doesNotContain(firstBot);
        assertThat(after.playerIds().subList(0, 2)).containsExactly(HOST, FRIEND);
        assertThat(after.botSeats()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("정원을 넘겨 앉히지 않는다 — 남은 자리만큼만, 꽉 찼으면 ROOM_FULL")
    void bots_never_exceed_capacity() {
        Room room = roomService.createRoom(HOST, "small", "ONE_CARD", TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, 0, 3);
        String id = room.roomId();

        Room filled = roomService.addBots(id, HOST, 5);
        assertThat(filled.playerIds()).hasSize(3);
        assertThatThrownBy(() -> roomService.addBots(id, HOST, 1)).isInstanceOf(RoomFullException.class);
        assertThat(roomService.getRoom(id).playerIds()).hasSize(3);
    }

    @Test
    @DisplayName("[봇 추가]를 여러 번 겹쳐 눌러도 정원(6)을 넘지 않고 같은 봇이 두 번 앉지 않는다")
    void concurrent_adds_stay_within_capacity() throws Exception {
        Room room = oneCardRoom();
        String id = room.roomId();
        int clicks = 12;
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < clicks; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        roomService.addBots(id, HOST, 1);
                        return true;
                    } catch (RoomFullException full) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int added = 0;
            for (Future<Boolean> f : results) {
                if (f.get(20, TimeUnit.SECONDS)) {
                    added++;
                }
            }
            assertThat(added).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        Room after = roomService.getRoom(id);
        assertThat(after.playerIds()).hasSize(6).doesNotHaveDuplicates();
        assertThat(botCount(after)).isEqualTo(5);
    }

    @Test
    @DisplayName("판돈 방에는 봇을 앉히지 못하고(D-81), 방장이 아니면 못 넣고 못 뺀다")
    void staked_rooms_and_non_hosts() {
        Room staked = roomService.createRoom(HOST, "staked", "TICHU", TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, 100, null);
        assertThatThrownBy(() -> roomService.addBots(staked.roomId(), HOST, 1))
                .isInstanceOf(StakedRoomNoBotsException.class);

        Room room = oneCardRoom();
        roomService.joinRoom(room.roomId(), FRIEND);
        long bot = roomService.addBots(room.roomId(), HOST, 1).playerIds().get(2);
        assertThatThrownBy(() -> roomService.addBots(room.roomId(), FRIEND, 1)).isInstanceOf(NotHostException.class);
        assertThatThrownBy(() -> roomService.removeBot(room.roomId(), FRIEND, bot)).isInstanceOf(NotHostException.class);
        assertThat(roomService.getRoom(room.roomId()).playerIds()).containsExactly(HOST, FRIEND, bot);
    }

    @Test
    @DisplayName("시작된 판에서는 봇을 넣지도 빼지도 못한다 — 좌석이 그대로다(D-122)")
    void a_started_game_keeps_its_seats() {
        Room room = oneCardRoom();
        String id = room.roomId();
        long bot = roomService.addBots(id, HOST, 1).playerIds().get(1);
        roomService.startNow(id, HOST);

        assertThatThrownBy(() -> roomService.removeBot(id, HOST, bot)).isInstanceOf(GameAlreadyStartedException.class);
        assertThatThrownBy(() -> roomService.addBots(id, HOST, 1)).isInstanceOf(GameAlreadyStartedException.class);
        assertThat(roomService.getRoom(id).playerIds()).containsExactly(HOST, bot);
    }

    @Test
    @DisplayName("사람이 나가 봇만 남으면 방이 사라진다 — 하나씩 넣은 봇도 같다(D-174)")
    void a_room_with_only_bots_is_removed() {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.addBots(id, HOST, 2);

        roomService.leaveRoom(id, HOST);

        assertThatThrownBy(() -> roomService.getRoom(id)).isInstanceOf(RoomNotFoundException.class);
    }

    // ---------- 대기실 강퇴 ----------

    @Test
    @DisplayName("방장이 내보낸 사람은 좌석·준비에서 빠지고 60초 동안 링크로 다시 앉지도 관전하지도 못한다 — 풀리면 다시 들어온다")
    void a_kicked_player_is_blocked_for_a_while() {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.joinRoom(id, FRIEND);
        roomService.setReady(id, FRIEND, true);

        Room after = roomService.kickFromLobby(id, HOST, FRIEND);

        assertThat(after.playerIds()).containsExactly(HOST);
        assertThat(after.readyUserIds()).isEmpty();
        assertThat(after.hostId()).isEqualTo(HOST);
        assertThatThrownBy(() -> roomService.joinOrReconnect(id, FRIEND)).isInstanceOf(KickedFromRoomException.class);
        assertThatThrownBy(() -> roomService.joinRoom(id, FRIEND)).isInstanceOf(KickedFromRoomException.class);
        assertThatThrownBy(() -> roomService.spectate(id, FRIEND)).isInstanceOf(KickedFromRoomException.class);
        Room still = roomService.getRoom(id);
        assertThat(still.playerIds()).containsExactly(HOST);
        assertThat(still.spectatorIds()).isEmpty();
        // 다른 사람은 그대로 들어온다.
        assertThat(roomService.joinOrReconnect(id, FRIEND_2).mode()).isEqualTo(JoinOrReconnectResult.Mode.JOINED);

        String banKey = "room:" + id + ":kicked:" + FRIEND;
        Long ttl = redis.getExpire(banKey, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, (long) RoomService.KICK_REJOIN_BLOCK_SECONDS);
        // 금지가 풀리면(만료) 같은 링크로 다시 들어온다.
        redis.delete(banKey);
        assertThat(roomService.joinOrReconnect(id, FRIEND).mode()).isEqualTo(JoinOrReconnectResult.Mode.JOINED);
    }

    @Test
    @DisplayName("강퇴는 방장만·대기실에서만 — 손님은 못 내보내고, 방장 자신은 대상이 아니다")
    void only_the_host_kicks_while_waiting() {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.joinRoom(id, FRIEND);
        roomService.joinRoom(id, FRIEND_2);

        assertThatThrownBy(() -> roomService.kickFromLobby(id, FRIEND, FRIEND_2)).isInstanceOf(NotHostException.class);
        assertThatThrownBy(() -> roomService.kickFromLobby(id, HOST, HOST)).isInstanceOf(NotInRoomException.class);

        roomService.startNow(id, HOST);
        assertThatThrownBy(() -> roomService.kickFromLobby(id, HOST, FRIEND)).isInstanceOf(GameAlreadyStartedException.class);
        assertThat(roomService.getRoom(id).playerIds()).containsExactly(HOST, FRIEND, FRIEND_2);
    }

    // ---------- 정원 조절 ----------

    @Test
    @DisplayName("정원을 줄이면 곧바로 입장·관전 판정에 닿는다 — 꽉 찬 방 링크는 관전으로, 늘리면 다시 앉는다")
    void capacity_change_applies_to_joins_right_away() {
        Room room = oneCardRoom();
        String id = room.roomId();
        assertThat(room.minCapacity()).isEqualTo(2);
        assertThat(room.maxCapacity()).isEqualTo(6);
        roomService.joinRoom(id, FRIEND);

        Room shrunk = roomService.updateSettings(id, HOST, RoomSettingsUpdate.ofCapacity(2));
        assertThat(shrunk.capacity()).isEqualTo(2);
        assertThat(roomService.listWaitingRooms("ONE_CARD"))
                .filteredOn(r -> r.roomId().equals(id)).singleElement()
                .satisfies(listed -> assertThat(listed.capacity()).isEqualTo(2));
        assertThat(roomService.joinOrReconnect(id, FRIEND_2).mode()).isEqualTo(JoinOrReconnectResult.Mode.SPECTATING);
        assertThatThrownBy(() -> roomService.joinRoom(id, FRIEND_2)).isInstanceOf(RoomFullException.class);

        Room grown = roomService.updateSettings(id, HOST, RoomSettingsUpdate.ofCapacity(3));
        assertThat(grown.capacity()).isEqualTo(3);
        assertThat(roomService.joinRoom(id, FRIEND_2).playerIds()).containsExactly(HOST, FRIEND, FRIEND_2);
        assertThat(roomService.getRoom(id).spectatorIds()).isEmpty();
    }

    @Test
    @DisplayName("지금 인원보다 작게·게임 범위 밖으로는 못 바꾼다 — 봇을 저절로 빼지 않는다")
    void capacity_bounds() {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.addBots(id, HOST, 3);

        assertThatThrownBy(() -> roomService.updateSettings(id, HOST, RoomSettingsUpdate.ofCapacity(3)))
                .isInstanceOf(TooManyPlayersException.class);
        assertThatThrownBy(() -> roomService.updateSettings(id, HOST, RoomSettingsUpdate.ofCapacity(7)))
                .isInstanceOf(InvalidCapacityException.class);
        assertThatThrownBy(() -> roomService.updateSettings(id, HOST, RoomSettingsUpdate.ofCapacity(1)))
                .isInstanceOf(InvalidCapacityException.class);
        Room same = roomService.getRoom(id);
        assertThat(same.capacity()).isEqualTo(6);
        assertThat(same.playerIds()).hasSize(4);
    }

    @Test
    @DisplayName("방장이 정한 정원은 판이 끝나 대기실로 돌아와도 그대로다(게임 최대로 되돌리지 않는다)")
    void the_hosts_capacity_survives_a_match() {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.joinRoom(id, FRIEND);
        roomService.updateSettings(id, HOST, RoomSettingsUpdate.ofCapacity(3));
        Room started = roomService.startNow(id, HOST);
        assertThat(started.capacity()).isEqualTo(2); // 시작은 현재 인원으로 줄인다(D-143)

        Room back = roomService.returnToWaiting(id);

        assertThat(back.status()).isEqualTo(RoomStatus.WAITING);
        assertThat(back.capacity()).isEqualTo(3);
        assertThat(back.maxCapacity()).isEqualTo(6);
    }

    // ---------- "초대 링크로 사람이 들어오면 봇이 같이 들어온다" ----------

    @Test
    @DisplayName("원인 확인 — 대기실에 링크로 들어오는 것만으로는 봇이 늘지 않는다")
    void joining_by_link_never_adds_bots() {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.addBots(id, HOST, 1);

        Room joined = roomService.joinOrReconnect(id, FRIEND).room();
        Room joined2 = roomService.joinOrReconnect(id, FRIEND_2).room();

        assertThat(botCount(joined)).isEqualTo(1);
        assertThat(botCount(joined2)).isEqualTo(1);
        assertThat(joined2.playerIds()).hasSize(4);
    }

    @Test
    @DisplayName("재현 — 봇으로 꽉 채워 하는 판에 링크로 온 친구는 관전자가 되고, 다음 판에 뒤쪽 봇 자리를 받는다(예전엔 봇이 자리를 쥐어 관전자로 남았다)")
    void friends_who_arrived_mid_game_take_bot_seats_next_round() throws Exception {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.addBots(id, HOST, null); // 정원(6)까지 — 방장 + 봇 5
        List<Long> seatedBots = roomService.getRoom(id).playerIds().subList(1, 6);
        roomService.startNow(id, HOST);

        // 판 도중 초대 링크로 두 명이 온다 — 자리가 없어 관전("이 판이 끝나면 다음 판에 함께해요").
        assertThat(roomService.joinOrReconnect(id, FRIEND).mode()).isEqualTo(JoinOrReconnectResult.Mode.SPECTATING);
        Thread.sleep(5);
        assertThat(roomService.joinOrReconnect(id, FRIEND_2).mode()).isEqualTo(JoinOrReconnectResult.Mode.SPECTATING);

        Room back = roomService.returnToWaiting(id);

        assertThat(back.capacity()).isEqualTo(6);
        assertThat(back.playerIds())
                .containsExactly(HOST, seatedBots.get(0), seatedBots.get(1), seatedBots.get(2), FRIEND, FRIEND_2);
        assertThat(back.spectatorIds()).isEmpty();
        assertThat(back.hostId()).isEqualTo(HOST);
    }

    @Test
    @DisplayName("빈자리가 있으면 봇은 그대로 두고 빈자리에 앉힌다 — 봇과 친구가 같이 대기실에 있게 된다(봇은 방장이 [✕]로 뺀다)")
    void with_free_seats_bots_stay() throws Exception {
        Room room = oneCardRoom();
        String id = room.roomId();
        roomService.addBots(id, HOST, 2);
        List<Long> seatedBots = roomService.getRoom(id).playerIds().subList(1, 3);
        roomService.startNow(id, HOST);
        roomService.joinOrReconnect(id, FRIEND);

        Room back = roomService.returnToWaiting(id);

        assertThat(back.playerIds()).containsExactly(HOST, seatedBots.get(0), seatedBots.get(1), FRIEND);
    }

    @Test
    @DisplayName("사람으로 꽉 찬 판의 관전자는 계속 관전한다 — 사람은 빼지 않는다")
    void humans_are_never_evicted() {
        Room room = roomService.createRoom(HOST, "humans", "ONE_CARD", TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, 0, 2);
        String id = room.roomId();
        roomService.joinRoom(id, FRIEND);
        roomService.startNow(id, HOST);
        roomService.joinOrReconnect(id, FRIEND_2);

        Room back = roomService.returnToWaiting(id);

        assertThat(back.capacity()).isEqualTo(2);
        assertThat(back.playerIds()).containsExactly(HOST, FRIEND);
        assertThat(back.spectatorIds()).containsExactly(FRIEND_2);
    }
}
