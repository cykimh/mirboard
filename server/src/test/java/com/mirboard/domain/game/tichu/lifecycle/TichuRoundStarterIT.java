package com.mirboard.domain.game.tichu.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=lifecycle-test-secret-must-be-32-bytes-or-more"
})
class TichuRoundStarterIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired
    RoomService roomService;

    @Autowired
    TichuGameStateStore stateStore;

    @Test
    void fourth_join_triggers_round_start_and_persists_initial_state() {
        Room room = roomService.createRoom(101L, "lifecycle-room", "TICHU");
        String roomId = room.roomId();
        roomService.joinRoom(roomId, 102L);
        roomService.joinRoom(roomId, 103L);
        Room afterFourth = roomService.joinRoom(roomId, 104L);

        assertThat(afterFourth.status()).isEqualTo(RoomStatus.IN_GAME);

        TichuState state = stateStore.load(roomId)
                .orElseThrow(() -> new AssertionError("State must be persisted after 4th join"));

        assertThat(state).isInstanceOf(TichuState.Playing.class);
        var playing = (TichuState.Playing) state;
        assertThat(playing.players()).hasSize(4);

        int totalCards = playing.players().stream()
                .mapToInt(p -> p.hand().size())
                .sum();
        assertThat(totalCards).isEqualTo(Deck.SIZE);   // 56 dealt
        assertThat(playing.players()).allMatch(p -> p.hand().size() == 14);
        assertThat(playing.trick().leadSeat()).isBetween(0, 3);
        assertThat(playing.trick().currentTop()).isNull();
    }

    @Test
    void persisted_state_round_trips_via_jackson() {
        Room room = roomService.createRoom(201L, "rt-room", "TICHU");
        String roomId = room.roomId();
        roomService.joinRoom(roomId, 202L);
        roomService.joinRoom(roomId, 203L);
        roomService.joinRoom(roomId, 204L);

        TichuState reloaded = stateStore.load(roomId).orElseThrow();
        // Save again and reload — confirms full round-trip including sealed type discriminator.
        stateStore.save(roomId, reloaded);
        TichuState second = stateStore.load(roomId).orElseThrow();

        assertThat(second).isInstanceOf(TichuState.Playing.class);
        assertThat(((TichuState.Playing) second).players()).hasSize(4);
    }

    @Test
    void each_player_hand_is_individually_persisted() {
        Room room = roomService.createRoom(301L, "hands-room", "TICHU");
        String roomId = room.roomId();
        roomService.joinRoom(roomId, 302L);
        roomService.joinRoom(roomId, 303L);
        roomService.joinRoom(roomId, 304L);

        for (long uid : new long[]{301L, 302L, 303L, 304L}) {
            var hand = stateStore.loadHand(roomId, uid)
                    .orElseThrow(() -> new AssertionError("Hand missing for user " + uid));
            assertThat(hand).hasSize(14);
        }
    }
}
