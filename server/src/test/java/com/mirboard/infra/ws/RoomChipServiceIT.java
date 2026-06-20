package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.RoomChipStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-82 — 방 단위 테이블 칩 정산 검증. 판돈이 방 안에서 승팀↔패팀으로 제로섬 이동하고,
 * 패자가 판돈보다 적으면 올인(보유분만), 봇/무판돈 매치는 정산 안 함.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-chip-test-secret-must-be-32-bytes-or-more"
})
class RoomChipServiceIT {

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
    RoomChipService service;

    @Autowired
    RoomChipStore store;

    @Autowired
    BotUserRegistry bots;

    private static TichuMatchCompleted teamAWins(String room, List<Long> ids, int stake) {
        return new TichuMatchCompleted(room, ids, 600, 200, Team.A,
                List.of(new RoundScore(600, 200, 0, false)), null, stake);
    }

    @Test
    void settlement_moves_stake_winners_plus_losers_minus() {
        String room = "chip-room-1";
        List<Long> ids = List.of(9001L, 9002L, 9003L, 9004L); // seat0,2=A · 1,3=B
        store.initIfAbsent(room, ids, RoomChipService.STARTING_STACK);

        service.onMatchCompleted(teamAWins(room, ids, 100));

        Map<Long, Long> s = store.stacks(room);
        assertThat(s.get(9001L)).isEqualTo(1100);
        assertThat(s.get(9003L)).isEqualTo(1100);
        assertThat(s.get(9002L)).isEqualTo(900);
        assertThat(s.get(9004L)).isEqualTo(900);
        // 제로섬 — 총합 보존.
        assertThat(s.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(4000);
        store.delete(room);
    }

    @Test
    void loser_goes_all_in_when_short_pot_splits_to_winners() {
        String room = "chip-room-2";
        List<Long> ids = List.of(9011L, 9012L, 9013L, 9014L);
        store.initIfAbsent(room, ids, 1000);
        // 패자 9012(seat1,B) 가 50 만 보유 → 판돈 100 중 50 만 올인.
        store.setStacks(room, Map.of(9011L, 1000L, 9012L, 50L, 9013L, 1000L, 9014L, 1000L));

        service.onMatchCompleted(teamAWins(room, ids, 100));

        Map<Long, Long> s = store.stacks(room);
        assertThat(s.get(9012L)).isEqualTo(0);    // 50 → 0 (올인)
        assertThat(s.get(9014L)).isEqualTo(900);  // 1000 → 900
        // 팟 = 50 + 100 = 150, 승자 2인 균등 75 → 1075 each.
        assertThat(s.get(9011L)).isEqualTo(1075);
        assertThat(s.get(9013L)).isEqualTo(1075);
        assertThat(s.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(3050);
        store.delete(room);
    }

    @Test
    void zero_stake_no_settlement() {
        String room = "chip-room-3";
        List<Long> ids = List.of(9021L, 9022L, 9023L, 9024L);
        store.initIfAbsent(room, ids, 1000);

        service.onMatchCompleted(teamAWins(room, ids, 0));

        assertThat(store.stacks(room).values()).allMatch(v -> v == 1000L);
        store.delete(room);
    }

    @Test
    void bot_match_no_settlement() {
        String room = "chip-room-4";
        long bot = bots.getBotIds().get(0);
        List<Long> ids = List.of(9031L, 9032L, 9033L, bot);
        store.initIfAbsent(room, ids, 1000);

        service.onMatchCompleted(new TichuMatchCompleted(room, ids, 900, 200, Team.A,
                List.of(new RoundScore(900, 200, 0, false)), null, 100));

        assertThat(store.stacks(room).values()).allMatch(v -> v == 1000L);
        store.delete(room);
    }

    @Test
    void init_if_absent_is_idempotent_for_rematch_carryover() {
        String room = "chip-room-5";
        List<Long> ids = List.of(9041L, 9042L, 9043L, 9044L);
        store.initIfAbsent(room, ids, 1000);
        store.setStacks(room, Map.of(9041L, 1300L, 9042L, 700L, 9043L, 1100L, 9044L, 900L));
        // 리매치 시 재호출 — 기존 칩 유지(덮어쓰지 않음).
        store.initIfAbsent(room, ids, 1000);
        assertThat(store.stacks(room).get(9041L)).isEqualTo(1300);
        assertThat(store.stacks(room).get(9042L)).isEqualTo(700);
        store.delete(room);
    }

    @Test
    void rebuy_tops_up_only_players_below_stake() {
        String room = "chip-room-6";
        List<Long> ids = List.of(9051L, 9052L, 9053L, 9054L);
        store.initIfAbsent(room, ids, 1000);
        store.setStacks(room, Map.of(9051L, 30L, 9052L, 1500L, 9053L, 1000L, 9054L, 0L));

        // 판돈 100 미만(9051=30, 9054=0)만 STARTING_STACK 으로 재바이인.
        store.rebuyBelow(room, ids, 100, RoomChipService.STARTING_STACK);

        Map<Long, Long> s = store.stacks(room);
        assertThat(s.get(9051L)).isEqualTo(RoomChipService.STARTING_STACK);
        assertThat(s.get(9054L)).isEqualTo(RoomChipService.STARTING_STACK);
        assertThat(s.get(9052L)).isEqualTo(1500); // 충분 → 유지
        assertThat(s.get(9053L)).isEqualTo(1000);
        store.delete(room);
    }
}
