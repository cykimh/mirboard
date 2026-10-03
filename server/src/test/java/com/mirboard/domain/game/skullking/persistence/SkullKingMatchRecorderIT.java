package com.mirboard.domain.game.skullking.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.skullking.event.SkullKingMatchCompleted;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** D-115 — 스컬킹 매치 종료가 매치 기록 + 게임별 전적(승패·개인전 ELO·탈주)으로 남는지. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=sk-recorder-test-secret-must-be-32-bytes-or-more"
})
class SkullKingMatchRecorderIT {

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

    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserGameStatsService stats;
    @Autowired UserRepository users;
    @Autowired BotUserRegistry bots;
    @Autowired JdbcTemplate jdbc;

    private long human() {
        String name = "sk" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats sk(long userId) {
        return stats.get(userId, SkullKingGameDefinition.ID);
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    @Test
    void human_match_records_rows_win_lose_and_free_for_all_elo() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();

        publisher.publishEvent(new SkullKingMatchCompleted(room, List.of(a, b, c),
                Map.of(0, 210, 1, 90, 2, -30), List.of(0), Set.of(), 10));

        Integer matches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skullking_match_results WHERE room_id = ? AND rounds_played = 10",
                Integer.class, room);
        assertThat(matches).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT p.user_id, p.seat, p.final_score, p.is_win FROM skullking_match_participants p"
                        + " JOIN skullking_match_results r ON r.id = p.match_id WHERE r.room_id = ?"
                        + " ORDER BY p.seat", room))
                .extracting(r -> r.get("final_score") + "/" + r.get("is_win"))
                .containsExactly("210/true", "90/false", "-30/false");

        // 신규(K=40), 3인 동일 레이팅: 1등 40/2 × (2 − 1) = +20, 2등 0, 3등 −20.
        assertThat(sk(a).rating()).isEqualTo(1020);
        assertThat(sk(b).rating()).isEqualTo(1000);
        assertThat(sk(c).rating()).isEqualTo(980);
        assertThat(sk(a).winCount()).isEqualTo(1);
        assertThat(sk(b).loseCount()).isEqualTo(1);
        assertThat(sk(c).loseCount()).isEqualTo(1);
    }

    @Test
    void bot_match_records_win_lose_but_skips_elo_and_bot_rows() {
        long a = human();
        long b = human();
        long bot = bots.getBotIds().get(0);

        publisher.publishEvent(new SkullKingMatchCompleted(room(), List.of(a, bot, b),
                Map.of(0, 100, 1, 300, 2, 50), List.of(1), Set.of(), 10));

        assertThat(sk(a).rating()).isEqualTo(1000);
        assertThat(sk(a).loseCount()).isEqualTo(1);
        assertThat(sk(b).loseCount()).isEqualTo(1);
        assertThat(stats.playedGames(bot)).isEmpty();
    }

    @Test
    void deserter_loses_gets_desert_count_and_ranks_last_despite_score() {
        long deserter = human();
        long stayer = human();

        publisher.publishEvent(new SkullKingMatchCompleted(room(), List.of(deserter, stayer),
                Map.of(0, 400, 1, 20), List.of(1), Set.of(0), 4));

        assertThat(sk(deserter).loseCount()).isEqualTo(1);
        assertThat(sk(deserter).desertCount()).isEqualTo(1);
        assertThat(sk(deserter).rating()).isLessThan(1000);
        assertThat(sk(stayer).winCount()).isEqualTo(1);
        assertThat(sk(stayer).desertCount()).isZero();
        assertThat(sk(stayer).rating()).isGreaterThan(1000);
    }
}
