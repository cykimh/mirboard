package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * D-153 — 정상 종료한 친구 방은 대기실로 돌아간다(room_return_waiting.lua): 정원을 게임 최대 인원으로, 준비를 비우고, 관전자를 들어온
 * 순서대로 빈자리에 앉히고, 방 목록에 다시 올린다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-return-waiting-test-secret-must-be-32-bytes"
})
class RoomReturnWaitingIT {

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

    @Autowired
    RoomService roomService;

    @Autowired
    StringRedisTemplate redis;

    private Room started(String gameType, List<Long> players) {
        Room room = roomService.createRoom(players.get(0), "next-round", gameType);
        for (Long uid : players.subList(1, players.size())) {
            roomService.joinRoom(room.roomId(), uid);
        }
        return roomService.startNow(room.roomId(), players.get(0));
    }

    private void spectateInOrder(String roomId, long... userIds) throws InterruptedException {
        for (long uid : userIds) {
            roomService.spectate(roomId, uid);
            Thread.sleep(5); // 들어온 시각(ms)이 갈리게.
        }
    }

    @Test
    void back_to_waiting_seats_spectators_in_arrival_order_and_restores_capacity() throws Exception {
        Room room = started("ONE_CARD", List.of(101L, 102L));
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.capacity()).isEqualTo(2);
        spectateInOrder(room.roomId(), 205L, 203L, 204L);

        Room back = roomService.returnToWaiting(room.roomId());

        assertThat(back.status()).isEqualTo(RoomStatus.WAITING);
        assertThat(back.capacity()).isEqualTo(6);
        assertThat(back.playerIds()).containsExactly(101L, 102L, 205L, 203L, 204L);
        assertThat(back.spectatorIds()).isEmpty();
        assertThat(redis.opsForSet().size("room:" + room.roomId() + ":ready")).isZero();
        assertThat(redis.opsForZSet().score("rooms:open", room.roomId())).isNotNull();
    }

    @Test
    void spectators_beyond_the_game_maximum_keep_watching() throws Exception {
        Room room = started("TICHU", List.of(111L, 112L, 113L, 114L));
        spectateInOrder(room.roomId(), 215L, 216L);

        Room back = roomService.returnToWaiting(room.roomId());

        assertThat(back.capacity()).isEqualTo(4);
        assertThat(back.playerIds()).containsExactly(111L, 112L, 113L, 114L);
        assertThat(back.spectatorIds()).containsExactlyInAnyOrder(215L, 216L);
    }

    @Test
    void a_spectator_who_left_is_not_seated() throws Exception {
        Room room = started("ONE_CARD", List.of(121L, 122L));
        spectateInOrder(room.roomId(), 223L, 224L);
        roomService.stopSpectating(room.roomId(), 223L);

        Room back = roomService.returnToWaiting(room.roomId());

        assertThat(back.playerIds()).containsExactly(121L, 122L, 224L);
    }

    @Test
    void only_a_room_in_game_goes_back() {
        Room waiting = roomService.createRoom(131L, "not-started", "ONE_CARD");

        Room same = roomService.returnToWaiting(waiting.roomId());

        assertThat(same.status()).isEqualTo(RoomStatus.WAITING);
        assertThat(same.playerIds()).containsExactly(131L);
    }
}
