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

/** D-115 — 게임별 전적 저장소의 읽기 기본값·누적 계약. D-305 — 통합 레이팅(게임 행과 같은 증감, 게스트 미저장, 통합 순위 왕관). D-199·D-209 — 이번 달 전적·랭킹(V19 는 이 컨텍스트 기동이 Flyway + validate 로 대조). */
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
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

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

        stats.record(me, game, true, 16, false);
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

        stats.record(me, gameA, true, 16, false);
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
        stats.record(me, game, true, 100, false);   // 누적(계급 근거)
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
    void every_ranking_takes_the_tier_from_the_unified_rating() {
        // D-305 — 전체든 게임별이든 계급·레이팅은 통합 레이팅 하나.
        LocalDate month = freshMonth();
        String gameA = freshGame();
        String gameB = freshGame();
        long spread = newUser();     // 두 게임에 3승
        long focused = newUser();    // 한 게임에 2승
        play(focused, gameA, month, "WW");
        play(spread, gameA, month, "WL");
        play(spread, gameB, month, "WW");
        // 통합 레이팅: 1000 → 1050(gameA) → 1210 → 1205(gameB).
        stats.record(spread, gameA, true, 50, false);
        stats.record(spread, gameB, true, 160, false);
        stats.record(spread, gameB, false, -5, false);

        List<MonthlyRankRow> rows = stats.monthlyRanking(null, month, 10);

        assertThat(rows).extracting(MonthlyRankRow::userId).containsExactly(spread, focused);
        assertThat(rows.getFirst().winCount()).isEqualTo(3);
        assertThat(rows.getFirst().loseCount()).isEqualTo(1);
        assertThat(rows.getFirst().rating()).isEqualTo(1205);
        assertThat(rows.getFirst().tier()).isEqualTo(Tier.fromRating(1205).name());
        // 통합 행이 없는 사람(이 테스트는 달만 직접 썼다)은 시작 계급.
        assertThat(rows.get(1).rating()).isEqualTo(1000);
        assertThat(rows.get(1).tier()).isEqualTo(Tier.fromRating(1000).name());
        // 게임별 랭킹에서도 같은 통합 값이다(그 게임에서 얻은 +50 이 아니라).
        assertThat(stats.monthlyRanking(gameA, month, 10)).filteredOn(r -> r.userId() == spread)
                .singleElement().satisfies(r -> {
                    assertThat(r.rating()).isEqualTo(1205);
                    assertThat(r.tier()).isEqualTo(Tier.fromRating(1205).name());
                });
    }

    // ---------- D-305 — 통합 레이팅 ----------

    @Test
    void every_game_moves_the_one_unified_rating_and_the_game_row_gets_the_same_change() {
        long me = newUser();
        String gameA = freshGame();
        String gameB = freshGame();
        assertThat(stats.ratingInput(me)).isEqualTo(new EloCalculator.PlayerInput(me, 1000, 0));

        stats.record(me, gameA, true, 20, false);      // 통합 1000 → 1020
        stats.record(me, gameB, false, -8, true);      // 통합 1020 → 1012 (gameB 에서 −8)
        stats.record(me, gameA, true, null, false);    // ELO 미적용 — 그대로

        GameStats unified = stats.unified(me);
        assertThat(unified.rating()).isEqualTo(1012);
        assertThat(unified.winCount()).isEqualTo(2);
        assertThat(unified.loseCount()).isEqualTo(1);
        assertThat(unified.desertCount()).isEqualTo(1);
        // 다음 판의 ELO 입력: 통합 레이팅 + 모든 게임 판 수.
        assertThat(stats.ratingInput(me)).isEqualTo(new EloCalculator.PlayerInput(me, 1012, 3));
        // 게임별 레이팅은 그 게임에서 얻고 잃은 만큼만(기록용).
        assertThat(stats.get(me, gameA).rating()).isEqualTo(1020);
        assertThat(stats.get(me, gameB).rating()).isEqualTo(992);
        // 통합 행은 게임 목록에 끼지 않는다.
        assertThat(stats.playedGames(me)).extracting(GameStats::gameType)
                .containsExactlyInAnyOrder(gameA, gameB);
        assertThat(stats.playsByGame()).extracting(UserGameStatsRepository.GamePlays::gameType)
                .doesNotContain(UserGameStats.ALL_GAMES);
        assertThat(stats.tierOf(users.findById(me).orElseThrow())).isEqualTo(Tier.fromRating(1012).name());
    }

    @Test
    void participation_bonus_goes_to_the_unified_rating_too_and_draws_keep_counts() {
        long me = newUser();
        String game = freshGame();

        stats.record(me, game, true, 20, false, EloCalculator.FULL_FACTOR);   // +20 +6
        stats.recordDraw(me, game, EloCalculator.FULL_FACTOR);                  // +6
        stats.record(me, game, false, null, true, EloCalculator.FULL_FACTOR);   // 탈주 — 참가 점수 없음

        int bonus = EloCalculator.participationBonus(EloCalculator.FULL_FACTOR, false);
        assertThat(stats.unified(me).rating()).isEqualTo(1020 + 2 * bonus);
        assertThat(stats.get(me, game).rating()).isEqualTo(1020 + 2 * bonus);
        assertThat(stats.unified(me).winCount()).isEqualTo(1);
        assertThat(stats.unified(me).loseCount()).isEqualTo(1);
    }

    /**
     * 같은 사람의 두 판(다른 방·다른 게임)이 거의 동시에 끝난다: 둘 다 같은 입력값(통합 1000)으로 ELO 를 계산해 놓고 기록한다.
     * 저장이 "지금 값 + 증감"이라 어느 쪽이 먼저 쓰든 두 증감이 모두 남아야 한다(절대값 저장이면 뒤 판이 앞 판을 지운다).
     */
    @Test
    void two_matches_finishing_at_once_both_count() throws Exception {
        long me = newUser();
        String gameA = freshGame();
        String gameB = freshGame();
        int rounds = 20;
        int bonus = EloCalculator.participationBonus(EloCalculator.FULL_FACTOR, false);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                // 두 기록기가 저마다 입력값을 먼저 읽는다 — 이 시점에는 서로의 결과를 모른다.
                EloCalculator.PlayerInput seenByA = stats.ratingInput(me);
                EloCalculator.PlayerInput seenByB = stats.ratingInput(me);
                assertThat(seenByA).isEqualTo(seenByB);
                java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);
                var a = pool.submit(() -> {
                    gate.await();
                    stats.record(me, gameA, true, 20, false, EloCalculator.FULL_FACTOR);
                    return null;
                });
                var b = pool.submit(() -> {
                    gate.await();
                    stats.record(me, gameB, false, -10, false, EloCalculator.FULL_FACTOR);
                    return null;
                });
                a.get(20, java.util.concurrent.TimeUnit.SECONDS);
                b.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 증감 합: 통합 = 두 게임의 합, 게임 행은 저마다 제 몫만.
        assertThat(stats.get(me, gameA).rating()).isEqualTo(1000 + rounds * (20 + bonus));
        assertThat(stats.get(me, gameB).rating()).isEqualTo(1000 + rounds * (-10 + bonus));
        GameStats unified = stats.unified(me);
        assertThat(unified.rating()).isEqualTo(1000 + rounds * (10 + 2 * bonus));
        assertThat(unified.winCount()).isEqualTo(rounds);
        assertThat(unified.loseCount()).isEqualTo(rounds);
    }

    /** 두 사람이 같이 낀 두 판이 동시에 끝나도 교착 없이 끝난다 — 기록기는 userId 오름차순으로 쓴다. */
    @Test
    void two_matches_sharing_two_players_do_not_deadlock_when_written_in_user_id_order() throws Exception {
        List<Long> seated = List.of(newUser(), newUser());
        List<Long> reversed = List.of(seated.get(1), seated.get(0));   // 다른 방에서는 좌석이 반대
        assertThat(UserGameStatsService.seatsInUserIdOrder(seated)).containsExactly(0, 1);
        assertThat(UserGameStatsService.seatsInUserIdOrder(reversed)).containsExactly(1, 0);
        String gameA = freshGame();
        String gameB = freshGame();
        int rounds = 20;
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);
                var a = pool.submit(() -> {
                    gate.await();
                    tx.executeWithoutResult(t -> {
                        for (int seat : UserGameStatsService.seatsInUserIdOrder(seated)) {
                            stats.record(seated.get(seat), gameA, seat == 0, seat == 0 ? 20 : -10, false);
                        }
                    });
                    return null;
                });
                var b = pool.submit(() -> {
                    gate.await();
                    tx.executeWithoutResult(t -> {
                        for (int seat : UserGameStatsService.seatsInUserIdOrder(reversed)) {
                            stats.record(reversed.get(seat), gameB, seat == 0, seat == 0 ? 20 : -10, false);
                        }
                    });
                    return null;
                });
                a.get(20, java.util.concurrent.TimeUnit.SECONDS);
                b.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 두 사람 모두 한 판은 +20, 한 판은 −10.
        assertThat(stats.unified(seated.get(0)).rating()).isEqualTo(1000 + rounds * 10);
        assertThat(stats.unified(seated.get(1)).rating()).isEqualTo(1000 + rounds * 10);
    }

    @Test
    void guests_get_no_unified_row_and_always_enter_at_1000() {
        long guest = users.save(User.create(GuestPolicy.newUsername(new SplittableRandom()),
                GuestPolicy.NO_LOGIN_HASH, Clock.systemUTC())).getId();
        String game = freshGame();

        stats.record(guest, game, true, 30, false, EloCalculator.FULL_FACTOR);
        stats.recordDraw(guest, game, EloCalculator.FULL_FACTOR);

        assertThat(stats.get(guest, game).rating()).isEqualTo(1000);
        assertThat(stats.get(guest, game).winCount()).isEqualTo(1);   // 게임 행은 남는다(끝낸 증거)
        assertThat(stats.unified(guest)).isEqualTo(new GameStats(UserGameStats.ALL_GAMES, 1000, 0, 0, 0));
        assertThat(stats.ratingInput(guest)).isEqualTo(new EloCalculator.PlayerInput(guest, 1000, 0));
    }

    @Test
    void crowns_follow_the_unified_rating_rank() {
        // D-164·D-305 — 금훈장 이상이면 통합 레이팅 순위로 왕관. 이 DB 에서 가장 높은 값으로 1위를 만든다.
        long top = newUser();
        long second = newUser();
        stats.record(top, freshGame(), true, 8_000, false);
        stats.record(second, freshGame(), true, 7_999, false);

        assertThat(stats.tierOf(users.findById(top).orElseThrow())).isEqualTo(Tier.STAR_CROWN.name());
        assertThat(stats.tierOf(users.findById(second).orElseThrow())).isEqualTo(Tier.GOLD_CROWN.name());
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
