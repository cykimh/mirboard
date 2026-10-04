package com.mirboard.domain.game.onecard.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
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

/** D-128 — 원카드 매치 종료가 매치 기록 + 게임별 전적(승패·개인전 ELO·탈주)으로 남는지. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=oc-recorder-test-secret-must-be-32-bytes-or-more"
})
class OneCardMatchRecorderIT {

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
        String name = "oc" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats oc(long userId) {
        return stats.get(userId, OneCardGameDefinition.ID);
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    private static MatchResult result(EndReason reason, Standing... standings) {
        return new MatchResult(reason, List.of(standings));
    }

    @Test
    void a_human_match_records_rows_win_lose_and_free_for_all_elo() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();

        publisher.publishEvent(new OneCardMatchCompleted(room, List.of(a, b, c), result(EndReason.FINISHED,
                new Standing(0, 1, 0, SeatStatus.FINISHED),
                new Standing(1, 2, 3, SeatStatus.ALIVE),
                new Standing(2, 3, 5, SeatStatus.ALIVE))));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM onecard_match_results WHERE room_id = ? AND end_reason = 'FINISHED'",
                Integer.class, room)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT p.final_rank, p.cards_left, p.is_win FROM onecard_match_participants p"
                        + " JOIN onecard_match_results r ON r.id = p.match_id WHERE r.room_id = ?"
                        + " ORDER BY p.seat", room))
                .extracting(r -> r.get("final_rank") + "/" + r.get("cards_left") + "/" + r.get("is_win"))
                .containsExactly("1/0/true", "2/3/false", "3/5/false");

        // 신규(K=40), 3인 동일 레이팅: 1등 40/2 × (2 − 1) = +20, 2등 0, 3등 −20.
        assertThat(oc(a).rating()).isEqualTo(1020);
        assertThat(oc(b).rating()).isEqualTo(1000);
        assertThat(oc(c).rating()).isEqualTo(980);
        assertThat(oc(a).winCount()).isEqualTo(1);
        assertThat(oc(b).loseCount()).isEqualTo(1);
        assertThat(oc(c).loseCount()).isEqualTo(1);
    }

    @Test
    void tied_first_places_all_win_and_draw_against_each_other() {
        long a = human();
        long b = human();
        long c = human();

        publisher.publishEvent(new OneCardMatchCompleted(room(), List.of(a, b, c), result(EndReason.STALEMATE,
                new Standing(0, 1, 4, SeatStatus.ALIVE),
                new Standing(1, 1, 4, SeatStatus.ALIVE),
                new Standing(2, 3, 9, SeatStatus.ALIVE))));

        assertThat(oc(a).winCount()).isEqualTo(1);
        assertThat(oc(b).winCount()).isEqualTo(1);
        assertThat(oc(c).loseCount()).isEqualTo(1);
        // 공동 1등끼리 무(0.5), 3등에게 승: (0.5−0.5 + 1−0.5) × 40/2 = +10. 3등은 −20.
        assertThat(oc(a).rating()).isEqualTo(1010);
        assertThat(oc(b).rating()).isEqualTo(1010);
        assertThat(oc(c).rating()).isEqualTo(980);
    }

    @Test
    void a_bot_match_records_win_lose_but_skips_elo_and_bot_rows() {
        long a = human();
        long b = human();
        long bot = bots.getBotIds().get(0);

        publisher.publishEvent(new OneCardMatchCompleted(room(), List.of(a, bot, b), result(EndReason.FINISHED,
                new Standing(1, 1, 0, SeatStatus.FINISHED),
                new Standing(0, 2, 2, SeatStatus.ALIVE),
                new Standing(2, 3, 6, SeatStatus.ALIVE))));

        assertThat(oc(a).rating()).isEqualTo(1000);
        assertThat(oc(a).loseCount()).isEqualTo(1);
        assertThat(oc(b).loseCount()).isEqualTo(1);
        assertThat(stats.playedGames(bot)).isEmpty();
    }

    @Test
    void a_deserter_loses_counts_a_desertion_and_ranks_below_a_bankrupt_seat() {
        long winner = human();
        long bankrupt = human();
        long deserter = human();

        publisher.publishEvent(new OneCardMatchCompleted(room(), List.of(winner, bankrupt, deserter),
                result(EndReason.LAST_STANDING,
                        new Standing(0, 1, 6, SeatStatus.ALIVE),
                        new Standing(1, 2, 20, SeatStatus.BANKRUPT),
                        new Standing(2, 3, 4, SeatStatus.DESERTED))));

        assertThat(oc(deserter).loseCount()).isEqualTo(1);
        assertThat(oc(deserter).desertCount()).isEqualTo(1);
        assertThat(oc(bankrupt).desertCount()).isZero();
        assertThat(oc(winner).winCount()).isEqualTo(1);
        assertThat(oc(deserter).rating()).isLessThan(oc(bankrupt).rating());
        assertThat(oc(bankrupt).rating()).isLessThan(oc(winner).rating());
    }
}
