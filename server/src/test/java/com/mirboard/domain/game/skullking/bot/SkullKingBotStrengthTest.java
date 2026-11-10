package com.mirboard.domain.game.skullking.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.bot.BotMatchHarness.Policy;
import com.mirboard.domain.game.skullking.bot.BotMatchHarness.Stat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-119·D-230 — 프로덕션 봇({@link Policy#MODEL}) 강도 평가 (필수 시뮬, Docker 불필요).
 * {@link BotMatchHarness} 로 고정 시드 매치를 돌려 "2~8인 모두에서 기존 봇(D-119 휴리스틱)·
 * 최약수 봇·무작위 봇을 이기는가"를 숫자로 강제한다.
 *
 * <p><b>하한은 실측 − 3·SE 이하</b>로 잡았다 — 시드가 고정이라 flaky 하지 않지만, 엔진 판정·
 * 분배가 바뀌어 수치가 이동해도 실질 퇴행이 아니면 넘도록. 하한이 의미 있다는 것은 대조군이
 * 같은 하한을 <b>못 넘는</b> 것으로 보인다 — 특히 8인 대 최약수는 기존 봇이 비겼던 국면이다.
 * 측정값은 {@code [D-230]} INFO 로그로 남는다. 판 수를 늘린 표는
 * {@link SkullKingBotEvaluationTest}(선택 실행).
 *
 * <p>시드: 평가 {@value #BASE} 계열. 상수는 이와 다른 555 계열에서 정했다.
 */
class SkullKingBotStrengthTest {

    private static final Logger log = LoggerFactory.getLogger(SkullKingBotStrengthTest.class);

    private static final long BASE = 20261003L;
    private static final int MATCHES = 40;
    private static final int MATCHES_SELF_PLAY = 10;

    /** 대 기존 봇 n별 점수 차 하한. 실측(40판) +131~+242, 최저 n=2 +131 (SE 30). */
    private static final double MIN_GAP_VS_HEURISTIC = 40;
    /** 대 최약수 봇 n별 점수 차 하한 — D-119 봇은 8인에서 −13 (SE 17). 실측 최저 n=8 +265 (SE 29). */
    private static final double MIN_GAP_VS_WEAKEST = 150;
    /** 대 무작위 봇 n별 점수 차 하한. 실측 최저 n=3 +326 (SE 22). */
    private static final double MIN_GAP_VS_RANDOM = 200;
    /** 대 무작위 봇 승률 하한. 실측 0.925~0.975. */
    private static final double MIN_SHARE_VS_RANDOM = 0.75;
    /** 자가대전 합산 예측 적중률 하한. 실측 0.680 (D-119 휴리스틱 자가대전 0.650). */
    private static final double MIN_SELF_PLAY_HIT_RATE = 0.55;
    /** 한 수 결정 시간 p99 상한 (사용자 결정, D-230). 개발 맥북 실측 8인 p99 3.6ms·최대 4.4ms. */
    private static final double MAX_P99_MILLIS = 20;

    /** 대 기존 봇 승률 하한 — 2인은 반을 넘어야 이긴 것이다. 실측 n=2 0.725, 그 밖 0.675~0.838. */
    private static double minShareVsHeuristic(int n) {
        return n == 2 ? 0.5 : 0.4;
    }

    /** 같은 시나리오를 두 테스트가 쓰므로 한 번만 돌린다 (결정적이라 캐시가 안전). */
    private static final Map<String, Stat> CACHE = new ConcurrentHashMap<>();

    private static Stat stat(String scenario, int n, int matches, long base,
                             int focalCount, Policy focal, Policy others) {
        return CACHE.computeIfAbsent(scenario + "/" + focal + "/n=" + n, key -> {
            Stat s = BotMatchHarness.run(n, matches, base, focalCount, focal, others);
            log.info("[D-230] {} gap={} (SE {}) share={} mean={} (SE {}) hit={}",
                    key, fmt(s.gap()), fmt(s.gapSe()), fmt3(s.share()),
                    fmt(s.mean()), fmt(s.meanSe()), fmt3(s.hitRate()));
            return s;
        });
    }

    private static Stat oneVs(Policy others, int n, Policy focal) {
        return stat("1-vs-" + others, n, MATCHES, BASE, 1, focal, others);
    }

    @Test
    void beats_the_previous_bot_at_every_table_size() {
        SoftAssertions.assertSoftly(soft -> {
            for (int n = 2; n <= 8; n++) {
                Stat s = oneVs(Policy.HEURISTIC, n, Policy.MODEL);
                soft.assertThat(s.gap()).as("n=%d 대 기존 봇 점수 차", n)
                        .isGreaterThanOrEqualTo(MIN_GAP_VS_HEURISTIC);
                soft.assertThat(s.share()).as("n=%d 대 기존 봇 승률", n)
                        .isGreaterThanOrEqualTo(minShareVsHeuristic(n));
            }
        });
    }

    @Test
    void beats_weakest_bots_where_the_previous_bot_could_not() {
        SoftAssertions.assertSoftly(soft -> {
            for (int n = 2; n <= 8; n++) {
                Stat s = oneVs(Policy.WEAKEST, n, Policy.MODEL);
                soft.assertThat(s.gap()).as("n=%d 대 최약수 점수 차", n)
                        .isGreaterThanOrEqualTo(MIN_GAP_VS_WEAKEST);
                soft.assertThat(s.mean()).as("n=%d 평균 점수", n).isPositive();
            }
            Stat previous = oneVs(Policy.WEAKEST, 8, Policy.HEURISTIC);
            soft.assertThat(previous.gap())
                    .as("대조군 — 8인 대 최약수에서 기존 봇은 하한을 못 넘는다 (%s)", previous)
                    .isLessThan(MIN_GAP_VS_WEAKEST);
        });
    }

    @Test
    void beats_random_bots() {
        SoftAssertions.assertSoftly(soft -> {
            for (int n = 2; n <= 8; n++) {
                Stat s = oneVs(Policy.RANDOM, n, Policy.MODEL);
                soft.assertThat(s.gap()).as("n=%d 대 무작위 점수 차", n)
                        .isGreaterThanOrEqualTo(MIN_GAP_VS_RANDOM);
                soft.assertThat(s.share()).as("n=%d 대 무작위 승률", n)
                        .isGreaterThanOrEqualTo(MIN_SHARE_VS_RANDOM);
            }
        });
    }

    @Test
    void same_seat_baselines_fail_the_bar() {
        SoftAssertions.assertSoftly(soft -> {
            for (int n = 2; n <= 8; n++) {
                // 같은 시드 = 같은 패. 그 좌석에 기존 봇·최약수가 앉으면 대 기존 봇 하한을 못 넘는다.
                Stat previous = oneVs(Policy.HEURISTIC, n, Policy.HEURISTIC);
                Stat weakest = oneVs(Policy.HEURISTIC, n, Policy.WEAKEST);
                soft.assertThat(previous.gap()).as("n=%d 기존 봇 좌석 (%s)", n, previous)
                        .isLessThan(MIN_GAP_VS_HEURISTIC);
                soft.assertThat(weakest.gap()).as("n=%d 최약수 좌석 (%s)", n, weakest)
                        .isLessThan(MIN_GAP_VS_HEURISTIC);
            }
        });
    }

    @Test
    void self_play_is_calibrated() {
        double hits = 0;
        int seats = 0;
        SoftAssertions soft = new SoftAssertions();
        for (int n = 2; n <= 8; n++) {
            Stat s = stat("self-play", n, MATCHES_SELF_PLAY, BASE + 90_000, n,
                    Policy.MODEL, Policy.MODEL);
            soft.assertThat(s.mean()).as("n=%d 자가대전 평균 점수", n).isPositive();
            hits += s.hitRate() * n;
            seats += n;
        }
        double hitRate = hits / seats;
        log.info("[D-230] self-play aggregate hit={}", fmt3(hitRate));
        soft.assertThat(hitRate).as("자가대전 합산 예측 적중률")
                .isGreaterThanOrEqualTo(MIN_SELF_PLAY_HIT_RATE);
        soft.assertAll();
    }

    @Test
    void decisions_stay_under_the_time_cap() {
        for (int n : new int[] {2, 8}) {
            long[] nanos = SkullKingBotEvaluationTest.decisionNanos(n, 2);
            double p99 = nanos[(int) (nanos.length * 0.99)] / 1e6;
            log.info("[D-230] n={} decisions={} p50={}ms p99={}ms max={}ms", n, nanos.length,
                    fmt3(nanos[nanos.length / 2] / 1e6), fmt3(p99),
                    fmt3(nanos[nanos.length - 1] / 1e6));
            assertThat(p99).as("n=%d 한 수 p99 (ms)", n).isLessThanOrEqualTo(MAX_P99_MILLIS);
        }
    }

    @Test
    void harness_is_reproducible() {
        Policy[] seats = {Policy.MODEL, Policy.HEURISTIC, Policy.WEAKEST, Policy.RANDOM};
        BotMatchHarness.MatchOutcome first = BotMatchHarness.play(4, BASE, seats);
        BotMatchHarness.MatchOutcome again = BotMatchHarness.play(4, BASE, seats);

        assertThat(again.scores()).as("같은 시드·같은 정책이면 같은 점수").isEqualTo(first.scores());
        assertThat(again.bidHits()).isEqualTo(first.bidHits());
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    private static String fmt3(double value) {
        return String.format("%.3f", value);
    }
}
