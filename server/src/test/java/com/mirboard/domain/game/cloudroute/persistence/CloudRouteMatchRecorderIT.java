package com.mirboard.domain.game.cloudroute.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.cloudroute.CloudRouteGameDefinition;
import com.mirboard.domain.game.cloudroute.event.CloudRouteMatchCompleted;
import com.mirboard.domain.game.cloudroute.state.MatchResult;
import com.mirboard.domain.game.cloudroute.state.MatchResult.EndReason;
import com.mirboard.domain.game.cloudroute.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.cloudroute.state.MatchResult.Standing;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
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

/** D-212 — 구름항로 매치 종료가 매치 기록 + 게임별 전적(승패·개인전 ELO·탈주)으로 남는지. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=cr-recorder-test-secret-must-be-32-bytes-or-more"
})
class CloudRouteMatchRecorderIT {

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
        String name = "cr" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats cr(long userId) {
        return stats.get(userId, CloudRouteGameDefinition.ID);
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    private static MatchResult result(EndReason reason, Standing... standings) {
        return new MatchResult(reason, 20, List.of(standings));
    }

    @Test
    void a_human_match_records_rows_win_lose_and_free_for_all_elo() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();

        publisher.publishEvent(new CloudRouteMatchCompleted(room, List.of(a, b, c), result(EndReason.ROUND_LIMIT,
                new Standing(1, 1, 3_400, 900, SeatStatus.ALIVE),
                new Standing(0, 2, 2_100, 300, SeatStatus.ALIVE),
                new Standing(2, 3, 0, 0, SeatStatus.BANKRUPT))));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cloudroute_match_results WHERE room_id = ? AND end_reason = 'ROUND_LIMIT' AND rounds = 20",
                Integer.class, room)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT p.final_rank, p.total_assets, p.status, p.is_win FROM cloudroute_match_participants p"
                        + " JOIN cloudroute_match_results r ON r.id = p.match_id WHERE r.room_id = ?"
                        + " ORDER BY p.seat", room))
                .extracting(r -> r.get("final_rank") + "/" + r.get("total_assets") + "/" + r.get("status") + "/" + r.get("is_win"))
                .containsExactly("2/2100/ALIVE/false", "1/3400/ALIVE/true", "3/0/BANKRUPT/false");

        // 신규(K=40), 3인 동일 레이팅: 1등 +20, 2등 0, 3등 −20.
        assertThat(cr(b).rating()).isEqualTo(1020);
        assertThat(cr(a).rating()).isEqualTo(1000);
        assertThat(cr(c).rating()).isEqualTo(980);
        assertThat(cr(b).winCount()).isEqualTo(1);
        assertThat(cr(a).loseCount()).isEqualTo(1);
    }

    @Test
    void a_bot_match_records_win_lose_but_skips_elo_and_bot_rows() {
        long a = human();
        long bot = bots.getBotIds().get(0);

        publisher.publishEvent(new CloudRouteMatchCompleted(room(), List.of(a, bot), result(EndReason.LAST_STANDING,
                new Standing(1, 1, 4_000, 2_000, SeatStatus.ALIVE),
                new Standing(0, 2, 0, 0, SeatStatus.BANKRUPT))));

        assertThat(cr(a).rating()).isEqualTo(1000);
        assertThat(cr(a).loseCount()).isEqualTo(1);
        assertThat(stats.playedGames(bot)).isEmpty();
    }

    @Test
    void a_deserter_loses_counts_a_desertion_and_ranks_below_a_bankrupt_seat() {
        long winner = human();
        long bankrupt = human();
        long deserter = human();

        publisher.publishEvent(new CloudRouteMatchCompleted(room(), List.of(winner, bankrupt, deserter),
                result(EndReason.LAST_STANDING,
                        new Standing(0, 1, 3_000, 1_000, SeatStatus.ALIVE),
                        new Standing(1, 2, 0, 0, SeatStatus.BANKRUPT),
                        new Standing(2, 3, 0, 0, SeatStatus.DESERTED))));

        assertThat(cr(deserter).loseCount()).isEqualTo(1);
        assertThat(cr(deserter).desertCount()).isEqualTo(1);
        assertThat(cr(bankrupt).desertCount()).isZero();
        assertThat(cr(deserter).rating()).isLessThan(cr(bankrupt).rating());
        assertThat(cr(bankrupt).rating()).isLessThan(cr(winner).rating());
    }

    @Test
    void head_to_head_compares_final_ranks() {
        long a = human();
        long b = human();
        publisher.publishEvent(new CloudRouteMatchCompleted(room(), List.of(a, b), result(EndReason.ROUND_LIMIT,
                new Standing(0, 1, 3_000, 1_000, SeatStatus.ALIVE),
                new Standing(1, 2, 2_000, 1_000, SeatStatus.ALIVE))));
        assertThat(new CloudRouteHeadToHead(jdbc).against(a))
                .containsExactly(new com.mirboard.domain.game.core.HeadToHeadSource.Row(b, 1, 0, 0));
    }
}
