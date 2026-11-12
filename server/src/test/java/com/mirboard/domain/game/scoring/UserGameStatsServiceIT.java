package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.scoring.UserGameStatsService.MonthlyRankRow;
import com.mirboard.domain.game.scoring.UserGameStatsService.Outcome;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
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

/** D-115 — 게임별 전적 저장소의 읽기 기본값·누적 계약. D-199·D-209 — 이번 달 전적·랭킹(V19 는 이 컨텍스트 기동이 Flyway + validate 로 대조). */
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

    // ---------- D-199·D-209 — 이번 달 전적·랭킹 ----------

    /** 다른 테스트(와 같은 DB 를 쓰는 전체 행)와 겹치지 않게 테스트마다 고유한 달을 쓴다. */
    private static LocalDate freshMonth() {
        return LocalDate.of(1900 + MONTH_SEQ.incrementAndGet(), 1, 1);
    }

    private static final java.util.concurrent.atomic.AtomicInteger MONTH_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private void play(long userId, String game, LocalDate month, String results) {
        for (char c : results.toCharArray()) {
            stats.recordMonthly(userId, game, month, c == 'W' ? Outcome.WIN : c == 'L' ? Outcome.LOSS : Outcome.DRAW,
                    false);
        }
    }

    @Test
    void recording_a_result_also_adds_to_this_months_game_row_and_all_games_row() {
        long me = newUser();
        String gameA = freshGame();
        String gameB = freshGame();

        stats.record(me, gameA, true, 1016, false);
        stats.record(me, gameB, false, null, true);
        stats.recordDraw(me, gameA);

        LocalDate month = stats.currentMonth();
        MonthlyRankRow a = stats.monthlyRanking(gameA, month, 100).stream()
                .filter(r -> r.userId() == me).findFirst().orElseThrow();
        assertThat(a.winCount()).isEqualTo(1);
        assertThat(a.loseCount()).isZero();
        assertThat(a.recent()).isEqualTo("WD");   // 무승부는 승·패에 세지 않고 최근 결과에만
        MonthlyRankRow all = stats.monthlyRanking(null, month, 100).stream()
                .filter(r -> r.userId() == me).findFirst().orElseThrow();
        assertThat(all.winCount()).isEqualTo(1);
        assertThat(all.loseCount()).isEqualTo(1);
        assertThat(all.desertCount()).isEqualTo(1);
        assertThat(all.winRate()).isEqualTo(50);
        assertThat(all.recent()).isEqualTo("WLD");   // 게임을 넘나들어 기록된 순서
        // 누적 전적은 그대로 쌓인다.
        assertThat(stats.get(me, gameA).winCount()).isEqualTo(1);
    }

    @Test
    void a_new_month_starts_from_zero_and_keeps_the_cumulative_record() {
        long me = newUser();
        String game = freshGame();
        LocalDate october = freshMonth();
        LocalDate november = october.plusMonths(1);
        stats.record(me, game, true, 1100, false);   // 누적(계급 근거)
        play(me, game, october, "WWL");
        play(me, game, november, "L");

        assertThat(stats.monthlyRanking(game, october, 10)).singleElement().satisfies(r -> {
            assertThat(r.winCount()).isEqualTo(2);
            assertThat(r.loseCount()).isEqualTo(1);
            assertThat(r.recent()).isEqualTo("WWL");
        });
        assertThat(stats.monthlyRanking(game, november, 10)).singleElement().satisfies(r -> {
            assertThat(r.winCount()).isZero();
            assertThat(r.loseCount()).isEqualTo(1);
            assertThat(r.recent()).isEqualTo("L");
            // 계급·레이팅은 달과 상관없이 누적에서.
            assertThat(r.rating()).isEqualTo(1100);
            assertThat(r.tier()).isEqualTo(Tier.fromRating(1100).name());
            assertThat(r.tierGameType()).isEqualTo(game);
        });
        assertThat(stats.monthlyRanking(game, november.plusMonths(1), 10)).isEmpty();
    }

    @Test
    void recent_keeps_the_last_five_oldest_first() {
        long me = newUser();
        String game = freshGame();
        LocalDate month = freshMonth();

        play(me, game, month, "WWLLWLW");

        assertThat(stats.monthlyRanking(game, month, 10).getFirst().recent()).isEqualTo("LLWLW");
        assertThat(stats.monthlyRanking(null, month, 10).getFirst().recent()).isEqualTo("LLWLW");
    }

    @Test
    void per_game_ranking_orders_by_wins_then_win_rate_then_games() {
        String game = freshGame();
        LocalDate month = freshMonth();
        long threeWins = newUser();
        long twoWinsClean = newUser();
        long twoWinsOneLoss = newUser();
        long noWinManyGames = newUser();
        long noWinOneGame = newUser();
        long otherGameOnly = newUser();
        play(noWinOneGame, game, month, "L");
        play(twoWinsOneLoss, game, month, "WLW");
        play(threeWins, game, month, "WLLLWW");
        play(noWinManyGames, game, month, "LLL");
        play(twoWinsClean, game, month, "WW");
        play(otherGameOnly, freshGame(), month, "WWWWW");

        List<MonthlyRankRow> rows = stats.monthlyRanking(game, month, 10);

        // 승수 → (같으면) 승률 → (승률도 같으면) 판수가 많은 쪽.
        assertThat(rows).extracting(MonthlyRankRow::userId)
                .containsExactly(threeWins, twoWinsClean, twoWinsOneLoss, noWinManyGames, noWinOneGame);
        assertThat(rows).extracting(MonthlyRankRow::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(rows).extracting(MonthlyRankRow::winRate).containsExactly(50, 100, 67, 0, 0);
        assertThat(rows.getFirst().username()).isNotBlank();
    }

    @Test
    void all_games_ranking_sums_every_game_and_takes_the_tier_from_the_most_played_game() {
        LocalDate month = freshMonth();
        String gameA = freshGame();
        String gameB = freshGame();
        long spread = newUser();     // 두 게임에 3승
        long focused = newUser();    // 한 게임에 2승
        play(focused, gameA, month, "WW");
        play(spread, gameA, month, "WL");
        play(spread, gameB, month, "WW");
        // 누적: spread 는 gameB 를 더 많이 했다(3판 > 1판).
        stats.record(spread, gameA, true, 1050, false);
        stats.record(spread, gameB, true, 1210, false);
        stats.record(spread, gameB, true, 1220, false);
        stats.record(spread, gameB, false, 1205, false);

        List<MonthlyRankRow> rows = stats.monthlyRanking(null, month, 10);

        assertThat(rows).extracting(MonthlyRankRow::userId).containsExactly(spread, focused);
        assertThat(rows.getFirst().winCount()).isEqualTo(3);
        assertThat(rows.getFirst().loseCount()).isEqualTo(1);
        assertThat(rows.getFirst().tierGameType()).isEqualTo(gameB);
        assertThat(rows.getFirst().rating()).isEqualTo(1205);
        assertThat(rows.getFirst().tier()).isEqualTo(Tier.fromRating(1205).name());
        // 누적 승·패가 없는 사람(이 테스트는 달만 직접 썼다)은 시작 계급.
        assertThat(rows.get(1).tierGameType()).isNull();
        assertThat(rows.get(1).tier()).isEqualTo(Tier.fromRating(1000).name());
    }

    @Test
    void monthly_ranking_excludes_bots_and_guests() {
        // D-117 — 일회용 게스트는 랭킹에 오르지 않는다. 봇은 기록기가 부르지 않지만 방어선 둘.
        String game = freshGame();
        LocalDate month = freshMonth();
        long low = newUser();
        long high = newUser();
        long bot = users.findBots().getFirst().getId();
        long guest = users.save(User.create(GuestPolicy.newUsername(new SplittableRandom()),
                GuestPolicy.NO_LOGIN_HASH, Clock.systemUTC())).getId();
        play(low, game, month, "L");
        play(high, game, month, "W");
        play(bot, game, month, "WWWW");
        play(guest, game, month, "WWW");

        assertThat(stats.monthlyRanking(game, month, 10)).extracting(MonthlyRankRow::userId)
                .containsExactly(high, low);
        assertThat(stats.monthlyRanking(null, month, 10)).extracting(MonthlyRankRow::userId)
                .containsExactly(high, low);
    }
}
