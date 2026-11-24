package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.starsquare.StarSquareGameDefinition;
import com.mirboard.domain.game.starsquare.event.StarSquareMatchCompleted;
import com.mirboard.domain.game.yacht.YachtGameDefinition;
import com.mirboard.domain.game.yacht.event.YachtMatchCompleted;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-299 — 봇이 낀 판의 레이팅(사람끼리의 1/4), 기록기 IT 가 따로 없는 두 게임(얏추·별자리 수판)과 게스트가 낀 판
 * (가입한 사람은 사람끼리 판처럼, 게스트 본인은 저장 안 함)·게스트+봇 판, 참가 점수(D-301).
 * 다른 일곱 게임은 각자의 {@code *MatchRecorderIT} 가 본다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=bot-rating-test-secret-must-be-32-bytes-or-more"
})
class BotMatchRatingIT {

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

    private long human() {
        String name = "br" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private long guest() {
        return users.save(User.create(GuestPolicy.newUsername(new SplittableRandom()),
                GuestPolicy.NO_LOGIN_HASH, Clock.systemUTC())).getId();
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    private GameStats yacht(long userId) {
        return stats.get(userId, YachtGameDefinition.ID);
    }

    private GameStats star(long userId) {
        return stats.get(userId, StarSquareGameDefinition.ID);
    }

    private void yachtMatch(List<Long> ids, int winnerSeat) {
        publisher.publishEvent(new YachtMatchCompleted(room(), ids,
                new com.mirboard.domain.game.yacht.state.MatchResult(
                        com.mirboard.domain.game.yacht.state.MatchResult.EndReason.FINISHED,
                        List.of(new com.mirboard.domain.game.yacht.state.MatchResult.Placement(
                                        winnerSeat, 1, 250, false),
                                new com.mirboard.domain.game.yacht.state.MatchResult.Placement(
                                        1 - winnerSeat, 2, 180, false)))));
    }

    private void starMatch(List<Long> ids, int winnerSeat) {
        publisher.publishEvent(new StarSquareMatchCompleted(room(), ids,
                new com.mirboard.domain.game.starsquare.state.MatchResult(
                        com.mirboard.domain.game.starsquare.state.MatchResult.EndReason.FINISHED,
                        List.of(new com.mirboard.domain.game.starsquare.state.MatchResult.Placement(
                                        winnerSeat, 1, 12, 3, false),
                                new com.mirboard.domain.game.starsquare.state.MatchResult.Placement(
                                        1 - winnerSeat, 2, 7, 1, false)))));
    }

    @Test
    void yacht_bot_match_moves_the_human_by_a_quarter_up_and_down() {
        long winner = human();
        long loser = human();
        long bot = bots.getBotIds().get(0);

        yachtMatch(List.of(winner, bot), 0);
        yachtMatch(List.of(loser, bot), 1);

        // 신규 K=40, 봇은 1000 으로 계산 → 사람끼리면 +20/−10, 봇 판은 +5/−2(−2.5 반올림). 참가 점수 +2(D-301).
        assertThat(yacht(winner).rating()).isEqualTo(1007);
        assertThat(yacht(winner).winCount()).isEqualTo(1);
        assertThat(yacht(loser).rating()).isEqualTo(1000);
        assertThat(yacht(loser).loseCount()).isEqualTo(1);
        assertThat(stats.playedGames(bot)).as("봇의 레이팅·전적은 저장하지 않는다").isEmpty();
    }

    @Test
    void star_square_bot_match_moves_the_human_by_a_quarter_up_and_down() {
        long winner = human();
        long loser = human();
        long bot = bots.getBotIds().get(0);

        starMatch(List.of(winner, bot), 0);
        starMatch(List.of(loser, bot), 1);

        assertThat(star(winner).rating()).isEqualTo(1007);
        assertThat(star(loser).rating()).isEqualTo(1000);
        assertThat(stats.playedGames(bot)).isEmpty();
    }

    @Test
    void humans_only_match_is_unchanged_full_gain_and_halved_loss() {
        long winner = human();
        long loser = human();

        yachtMatch(List.of(winner, loser), 0);

        assertThat(yacht(winner).rating()).isEqualTo(1026);
        assertThat(yacht(loser).rating()).isEqualTo(996);
        // D-305 — 통합 레이팅이 같이 움직인다(첫 판이라 게임별 값과 같다).
        assertThat(stats.unified(winner).rating()).isEqualTo(1026);
        assertThat(stats.unified(loser).rating()).isEqualTo(996);
    }

    @Test
    void a_guest_match_rates_the_member_like_a_humans_match_and_never_stores_the_guest_rating() {
        long member = human();
        long guest = guest();
        long bot = bots.getBotIds().get(0);

        yachtMatch(List.of(member, guest), 0);
        starMatch(List.of(member, guest), 0);
        // 가입한 사람이 게스트에게 이김: 사람끼리 판처럼 +20(K=40) + 참가 점수 +6. 진 게스트는 1000 그대로.
        assertThat(yacht(member).rating()).isEqualTo(1026);
        // D-305 — 두 번째 판(별자리)의 ELO 입력은 통합 레이팅 1026·1판: 40 × (1 − 0.537) ≈ +19, 참가 점수 +6.
        // 게임별 레이팅은 그 게임에서 얻은 만큼만(1000 + 25), 통합은 둘의 합.
        assertThat(star(member).rating()).isEqualTo(1025);
        assertThat(stats.unified(member).rating()).isEqualTo(1051);
        assertThat(stats.unified(guest).rating()).as("게스트는 통합 행도 없다").isEqualTo(1000);
        assertThat(stats.unified(guest).gamesPlayed()).isZero();
        assertThat(yacht(guest).rating()).isEqualTo(1000);
        assertThat(yacht(guest).loseCount()).as("게스트도 승패 행은 남는다").isEqualTo(1);

        // 게스트 + 봇 + 정회원 — 봇 판 규칙(1/4). 게스트가 이기고 정회원이 꼴찌.
        publisher.publishEvent(new StarSquareMatchCompleted(room(), List.of(member, guest, bot),
                new com.mirboard.domain.game.starsquare.state.MatchResult(
                        com.mirboard.domain.game.starsquare.state.MatchResult.EndReason.FINISHED,
                        List.of(new com.mirboard.domain.game.starsquare.state.MatchResult.Placement(1, 1, 12, 3, false),
                                new com.mirboard.domain.game.starsquare.state.MatchResult.Placement(2, 2, 9, 1, false),
                                new com.mirboard.domain.game.starsquare.state.MatchResult.Placement(0, 3, 4, 0, false)))));

        // 통합 1051 인 정회원이 1000 둘(게스트·봇)에게 짐: 40 × (0 − 0.573) × ½ × ¼ ≈ −2.9 → −3, 참가 점수 +2.
        assertThat(star(member).rating()).isEqualTo(1024);
        assertThat(stats.unified(member).rating()).isEqualTo(1050);
        assertThat(stats.unified(member).gamesPlayed()).isEqualTo(3);
        assertThat(star(member).winCount()).isEqualTo(1);
        assertThat(star(member).loseCount()).isEqualTo(1);
        assertThat(star(guest).rating()).as("이긴 게스트도 레이팅은 저장하지 않는다").isEqualTo(1000);
        assertThat(star(guest).winCount()).isEqualTo(1);
        assertThat(stats.playedGames(bot)).isEmpty();
    }
}
