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
 * D-119 — 봇 강도 평가 (필수 시뮬, Docker 불필요). {@link BotMatchHarness} 로 고정 시드
 * 매치를 돌려 "휴리스틱이 D-119 이전 봇(균등 무작위)을 확실히 이기는가"를 숫자로 강제한다.
 *
 * <p><b>하한은 실측 − 3·SE 이하</b>로 잡았다 — 시드가 고정이라 flaky 하지 않지만, 엔진
 * 판정·분배가 바뀌어 수치가 이동해도 실질 퇴행이 아니면 넘도록. 하한이 의미 있다는 것은
 * 대조군(같은 좌석·같은 패에 무작위·최약수 정책)이 같은 하한을 <b>못 넘는</b> 것으로
 * 보인다. 측정값은 INFO 로그로 남는다(D-119 기록의 출처).
 *
 * <p>시드: 평가 {@value #BASE} 계열. 상수 5개는 이와 다른 시드 계열로 정했고, 홀드아웃
 * 777 계열에서도 같은 하한을 넘는다 (D-119).
 */
class SkullKingBotStrengthTest {

    private static final Logger log = LoggerFactory.getLogger(SkullKingBotStrengthTest.class);

    private static final long BASE = 20261003L;

    /** 1:무작위 · 2:무작위 공통 — 초점 평균 − 상대 평균 하한. 실측 최저 269 (SE 13~20). */
    private static final double MIN_GAP = 150;
    /** 1:무작위 승률 하한 (기준 1/n). 실측 0.92~1.00. */
    private static final double MIN_SHARE = 0.75;
    /** 2:무작위 진영 승률 하한. 실측 1.00. */
    private static final double MIN_SIDE_SHARE = 0.9;
    /** 자가대전 합산 예측 적중률 하한. 실측 0.650 (무작위 자가대전 약 0.20). */
    private static final double MIN_SELF_PLAY_HIT_RATE = 0.5;
    /** 대 최약수 봇 n별 점수 차 하한 — 8인이 대등(+1.6, SE 23)이라 실측 − 3SE 를 내림. */
    private static final double MIN_GAP_VS_WEAKEST_PER_N = -75;

    private static final int MATCHES = 50;
    private static final int MATCHES_SMALL = 30;

    /** 같은 시나리오를 두 테스트가 쓰므로 한 번만 돌린다 (결정적이라 캐시가 안전). */
    private static final Map<String, Stat> CACHE = new ConcurrentHashMap<>();

    private static Stat stat(String scenario, int n, int matches, long base,
                             int focalCount, Policy focal, Policy others) {
        return CACHE.computeIfAbsent(scenario + "/n=" + n, key -> {
            Stat s = BotMatchHarness.run(n, matches, base, focalCount, focal, others);
            log.info("[D-119] {} gap={} (SE {}) share={} mean={} (SE {}) hit={}",
                    key, fmt(s.gap()), fmt(s.gapSe()), fmt3(s.share()),
                    fmt(s.mean()), fmt(s.meanSe()), fmt3(s.hitRate()));
            return s;
        });
    }

    /** 휴리스틱 1명 대 무작위 n−1명. */
    private static Stat oneVsRandom(int n, Policy focal) {
        return stat("1-vs-random/" + focal, n, MATCHES, BASE, 1, focal, Policy.RANDOM);
    }

    @Test
    void one_heuristic_beats_random_bots() {
        SoftAssertions.assertSoftly(soft -> {
            for (int n = 2; n <= 8; n++) {
                Stat s = oneVsRandom(n, Policy.HEURISTIC);
                soft.assertThat(s.gap()).as("n=%d 점수 차", n).isGreaterThanOrEqualTo(MIN_GAP);
                soft.assertThat(s.share()).as("n=%d 승률", n).isGreaterThanOrEqualTo(MIN_SHARE);
                soft.assertThat(s.mean()).as("n=%d 평균 점수", n).isPositive();
            }
        });
    }

    @Test
    void baseline_policies_in_the_same_seat_fail_the_bar() {
        SoftAssertions.assertSoftly(soft -> {
            for (int n = 2; n <= 8; n++) {
                Stat random = oneVsRandom(n, Policy.RANDOM);
                Stat weakest = oneVsRandom(n, Policy.WEAKEST);
                soft.assertThat(random.clears(MIN_GAP, MIN_SHARE))
                        .as("n=%d 무작위 좌석은 하한을 못 넘어야 한다 (%s)", n, random)
                        .isFalse();
                soft.assertThat(weakest.clears(MIN_GAP, MIN_SHARE))
                        .as("n=%d 최약수 좌석은 하한을 못 넘어야 한다 (%s)", n, weakest)
                        .isFalse();

                // 같은 시드 = 같은 패 — 그 좌석에 휴리스틱이 앉았을 때와 최약수가 앉았을 때.
                double paired = oneVsRandom(n, Policy.HEURISTIC).mean() - weakest.mean();
                soft.assertThat(paired)
                        .as("n=%d 같은 패에서 휴리스틱 좌석 평균 − 최약수 좌석 평균", n)
                        .isGreaterThanOrEqualTo(MIN_GAP);
            }
        });
    }

    @Test
    void two_heuristics_beat_random_bots() {
        SoftAssertions.assertSoftly(soft -> {
            for (int n : new int[] {4, 6, 8}) {
                Stat s = stat("2-vs-random", n, MATCHES_SMALL, BASE + 50_000, 2,
                        Policy.HEURISTIC, Policy.RANDOM);
                soft.assertThat(s.share()).as("n=%d 휴리스틱 진영 승률", n)
                        .isGreaterThanOrEqualTo(MIN_SIDE_SHARE);
                soft.assertThat(s.gap()).as("n=%d 점수 차", n).isGreaterThanOrEqualTo(MIN_GAP);
            }
        });
    }

    @Test
    void heuristic_self_play_is_calibrated() {
        double hits = 0;
        int seats = 0;
        SoftAssertions soft = new SoftAssertions();
        for (int n = 2; n <= 8; n++) {
            Stat s = stat("self-play", n, MATCHES_SMALL, BASE + 90_000, n,
                    Policy.HEURISTIC, Policy.HEURISTIC);
            soft.assertThat(s.mean()).as("n=%d 자가대전 평균 점수", n).isPositive();
            // n 마다 매치 수·라운드 수가 같으므로 좌석 수로 가중하면 전체 좌석-라운드 합산.
            hits += s.hitRate() * n;
            seats += n;
        }
        double hitRate = hits / seats;
        log.info("[D-119] self-play aggregate hit={}", fmt3(hitRate));
        soft.assertThat(hitRate).as("자가대전 합산 예측 적중률")
                .isGreaterThanOrEqualTo(MIN_SELF_PLAY_HIT_RATE);
        soft.assertAll();
    }

    @Test
    void heuristic_is_not_exploited_by_weakest_bots() {
        double gapSum = 0;
        SoftAssertions soft = new SoftAssertions();
        for (int n = 2; n <= 8; n++) {
            Stat s = stat("1-vs-weakest", n, MATCHES, BASE + 130_000, 1,
                    Policy.HEURISTIC, Policy.WEAKEST);
            soft.assertThat(s.gap()).as("n=%d 대 최약수 점수 차", n)
                    .isGreaterThanOrEqualTo(MIN_GAP_VS_WEAKEST_PER_N);
            gapSum += s.gap();
        }
        double pooled = gapSum / 7;
        log.info("[D-119] 1-vs-weakest pooled gap={}", fmt(pooled));
        soft.assertThat(pooled).as("n 합산 평균 점수 차").isGreaterThanOrEqualTo(MIN_GAP);
        soft.assertAll();
    }

    @Test
    void harness_is_paired_and_reproducible() {
        Policy[] seats = {Policy.HEURISTIC, Policy.RANDOM, Policy.WEAKEST, Policy.RANDOM};
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
