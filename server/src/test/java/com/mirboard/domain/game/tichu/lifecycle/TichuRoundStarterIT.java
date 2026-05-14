package com.mirboard.domain.game.tichu.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import java.util.HashSet;
import java.util.Set;
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

    @Autowired
    TichuMatchStateStore matchStateStore;

    @Test
    void fourth_join_triggers_round_start_in_dealing_phase_8() {
        Room room = roomService.createRoom(101L, "lifecycle-room", "TICHU");
        String roomId = room.roomId();
        roomService.joinRoom(roomId, 102L);
        roomService.joinRoom(roomId, 103L);
        Room afterFourth = roomService.joinRoom(roomId, 104L);

        assertThat(afterFourth.status()).isEqualTo(RoomStatus.IN_GAME);

        TichuState state = stateStore.load(roomId)
                .orElseThrow(() -> new AssertionError("State must be persisted after 4th join"));

        assertThat(state).isInstanceOf(TichuState.Dealing.class);
        var dealing = (TichuState.Dealing) state;
        assertThat(dealing.phaseCardCount()).isEqualTo(8);
        assertThat(dealing.players()).hasSize(4);
        assertThat(dealing.ready()).isEmpty();

        // 각 좌석: 8장 visible + 6장 reserved → 합 14장.
        int totalVisible = dealing.players().stream()
                .mapToInt(p -> p.hand().size()).sum();
        int totalReserved = dealing.reservedSecondHalf().values().stream()
                .mapToInt(java.util.List::size).sum();
        assertThat(totalVisible + totalReserved).isEqualTo(Deck.SIZE);
        assertThat(dealing.players()).allMatch(p -> p.hand().size() == 8);
        assertThat(dealing.reservedSecondHalf().values()).allMatch(list -> list.size() == 6);

        // 56장 중복 없음 — visible + reserved 합집합 카드 set 크기 = 56.
        Set<Card> allCards = new HashSet<>();
        dealing.players().forEach(p -> allCards.addAll(p.hand()));
        dealing.reservedSecondHalf().values().forEach(allCards::addAll);
        assertThat(allCards).hasSize(Deck.SIZE);
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

        assertThat(second).isInstanceOf(TichuState.Dealing.class);
        assertThat(((TichuState.Dealing) second).players()).hasSize(4);
        assertThat(((TichuState.Dealing) second).phaseCardCount()).isEqualTo(8);
        assertThat(((TichuState.Dealing) second).reservedSecondHalf()).hasSize(4);
    }

    @Test
    void match_state_is_initialized_on_first_round() {
        Room room = roomService.createRoom(401L, "match-init-room", "TICHU");
        String roomId = room.roomId();
        roomService.joinRoom(roomId, 402L);
        roomService.joinRoom(roomId, 403L);
        roomService.joinRoom(roomId, 404L);

        var matchState = matchStateStore.load(roomId)
                .orElseThrow(() -> new AssertionError("match state must exist after game start"));
        assertThat(matchState.roundNumber()).isEqualTo(1);
        assertThat(matchState.cumulativeA()).isZero();
        assertThat(matchState.cumulativeB()).isZero();
        assertThat(matchState.playerIds()).containsExactly(401L, 402L, 403L, 404L);
    }

    @Test
    void each_player_visible_hand_is_individually_persisted() {
        Room room = roomService.createRoom(301L, "hands-room", "TICHU");
        String roomId = room.roomId();
        roomService.joinRoom(roomId, 302L);
        roomService.joinRoom(roomId, 303L);
        roomService.joinRoom(roomId, 304L);

        for (long uid : new long[]{301L, 302L, 303L, 304L}) {
            var hand = stateStore.loadHand(roomId, uid)
                    .orElseThrow(() -> new AssertionError("Hand missing for user " + uid));
            // Phase 5b: 첫 분배에서 좌석별 8장만 비공개 큐로 노출.
            assertThat(hand).hasSize(8);
        }
    }
}
