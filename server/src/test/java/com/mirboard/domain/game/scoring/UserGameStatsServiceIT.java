package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
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

/** D-115 — 게임별 전적 저장소의 읽기 기본값·누적·랭킹 계약. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=game-stats-test-secret-must-be-32-bytes-or-more"
})
class UserGameStatsServiceIT {

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

    @Autowired UserGameStatsService stats;
    @Autowired UserRepository users;

    private long newUser() {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    /** 다른 테스트와 겹치지 않게 테스트마다 고유한 게임 타입을 쓴다. */
    private static String freshGame() {
        return "G" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    @Test
    void unplayed_game_reads_as_defaults() {
        long me = newUser();

        GameStats s = stats.get(me, freshGame());

        assertThat(s.rating()).isEqualTo(1000);
        assertThat(s.winCount()).isZero();
        assertThat(s.loseCount()).isZero();
        assertThat(s.desertCount()).isZero();
        assertThat(stats.playedGames(me)).isEmpty();
    }

    @Test
    void results_accumulate_and_null_rating_keeps_the_current_rating() {
        long me = newUser();
        String game = freshGame();

        stats.record(me, game, true, 1016, false);
        stats.record(me, game, false, null, true);   // 봇 매치처럼 ELO 미적용 + 탈주

        GameStats s = stats.get(me, game);
        assertThat(s.rating()).isEqualTo(1016);
        assertThat(s.winCount()).isEqualTo(1);
        assertThat(s.loseCount()).isEqualTo(1);
        assertThat(s.desertCount()).isEqualTo(1);
        assertThat(stats.playedGames(me)).extracting(GameStats::gameType).containsExactly(game);
    }

    @Test
    void first_result_without_elo_starts_from_the_default_rating() {
        long me = newUser();
        String game = freshGame();

        stats.record(me, game, true, null, false);

        assertThat(stats.get(me, game).rating()).isEqualTo(1000);
    }

    @Test
    void ranking_is_per_game_ordered_by_rating_and_excludes_bots() {
        String game = freshGame();
        long low = newUser();
        long high = newUser();
        long otherGameOnly = newUser();
        long bot = users.findBots().getFirst().getId();
        stats.record(low, game, false, 990, false);
        stats.record(high, game, true, 1010, false);
        stats.record(bot, game, true, 2000, false);
        stats.record(otherGameOnly, freshGame(), true, 1500, false);

        List<UserGameStatsService.RankRow> rows = stats.ranking(game, 10);

        assertThat(rows).extracting(UserGameStatsService.RankRow::userId).containsExactly(high, low);
        assertThat(rows.getFirst().username()).isNotBlank();
    }
}
