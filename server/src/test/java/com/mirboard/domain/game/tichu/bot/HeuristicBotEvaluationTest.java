package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * D-118 대형 평가 — {@code MIRBOARD_BOT_EVAL=1} 일 때만 돈다(기본 스위트·CI 제외).
 * {@code docs/plans/tichu-bot-heuristic.md} 와 D-118 의 수치는 이 테스트의 출력이 출처다.
 *
 * <pre>
 * MIRBOARD_BOT_EVAL=1 ./gradlew :server:test --rerun \
 *     --tests "com.mirboard.domain.game.tichu.bot.HeuristicBotEvaluationTest"
 * </pre>
 * {@code --rerun} 이 필요하다 — 환경변수는 Gradle up-to-date 입력이 아니라 그냥 돌리면
 * 이전 결과로 건너뛴다.
 */
@EnabledIfEnvironmentVariable(named = "MIRBOARD_BOT_EVAL", matches = "1")
class HeuristicBotEvaluationTest {

    /** 사전 등록 목표 — 사람 상대 하락 여유를 두려고 손익분기 0.5 보다 높게. */
    private static final double TICHU_TARGET = 0.70;
    private static final double GRAND_TARGET = 0.55;
    private static final int GRAND_MIN_SAMPLE = 30;

    @Test
    void random_baseline_1000_matches() {
        var arena = new TichuBotArena();
        var stats = new TichuBotArena.Stats();
        int wins = 0;
        int matches = 0;
        int rounds = 0;
        for (long deck = 10_000; deck < 10_500; deck++) {
            for (int swap = 0; swap < 2; swap++) {
                Team heuristicTeam = swap == 0 ? Team.A : Team.B;
                var seats = new TichuBotArena.Seat[4];
                for (int s = 0; s < 4; s++) {
                    seats[s] = Team.ofSeat(s) == heuristicTeam
                            ? TichuBotArena.heuristic()
                            : TichuBotArena.random(deck * 10 + s);
                }
                var r = arena.playMatch(seats, deck, stats);
                matches++;
                rounds += r.rounds();
                if (r.winner() == heuristicTeam) wins++;
            }
        }
        double[] ci = wilson(wins, matches);
        System.out.printf("[D-118 eval] vs random: %d/%d (%.3f, 95%% CI %.3f–%.3f),"
                        + " avg rounds %.2f, double victories %d/%d, fellBack %d%n",
                wins, matches, (double) wins / matches, ci[0], ci[1],
                (double) rounds / matches, stats.doubleVictories, stats.rounds, stats.fellBack);
        assertThat(stats.fellBack).isZero();
    }

    @Test
    void greedy_baseline_1000_deals() {
        var arena = new TichuBotArena();
        var stats = new TichuBotArena.Stats();
        var deltas = HeuristicBotSimulationTest.duplicateDeltas(arena, stats,
                TichuBotArena.heuristic(), TichuBotArena.greedy(), 20_000, 1000);
        var sum = HeuristicBotSimulationTest.DuplicateSummary.of(deltas);
        System.out.println("[D-118 eval] vs greedy: " + sum + ", fellBack " + stats.fellBack);
        assertThat(stats.fellBack).isZero();
        assertThat((double) sum.positive()).isGreaterThanOrEqualTo(sum.threshold());
    }

    /**
     * 선언 보정 표본(in-sample) — 문턱을 고를 때 본 시드 구간. 1·2차 보정은 30000~30199,
     * 3차 보정은 리뷰가 보정 밖으로 돌린 50000~70199 를 더했다(그래서 이 구간도 이제 보정 표본).
     * 목표 단언은 하지 않고 구간별 성공률·문턱 구간 표만 낸다 — 목표는
     * {@link #self_play_holdout_meets_declaration_targets} 가 보정에 쓰지 않은 시드로 단언한다.
     */
    private static final long[][] CALIBRATION_SEEDS = {
            {30_000, 200}, {50_000, 200}, {60_000, 200}, {70_000, 200}};

    /**
     * 검증 표본(held-out) — 문턱을 확정한 <b>뒤에</b> 한 번만 돌리려고 정해 둔 구간. 보정에
     * 다시 쓰면 안 된다(쓰게 되면 그 구간은 {@link #CALIBRATION_SEEDS} 로 옮기고 새 구간을 정한다).
     */
    private static final long HOLDOUT_FIRST_SEED = 100_000;
    private static final int HOLDOUT_MATCHES = 600;

    /** 자가대전 보정 표본 800매치 — 구간별 성공률과 문턱 구간(controls·비컨트롤 묶음)별 표. */
    @Test
    void self_play_calibration_log() {
        var calibration = new Calibration();
        var total = new TichuBotArena.Stats();
        for (long[] range : CALIBRATION_SEEDS) {
            var stats = selfPlay(calibration, range[0], (int) range[1]);
            System.out.printf("[D-118 eval] calibration seeds %d~%d: %s%n",
                    range[0], range[0] + range[1] - 1, summary(stats));
            assertThat(stats.fellBack).isZero();
            total.tichuCalls += stats.tichuCalls;
            total.tichuMade += stats.tichuMade;
            total.grandCalls += stats.grandCalls;
            total.grandMade += stats.grandMade;
        }
        System.out.printf("[D-118 eval] calibration total: tichu %d/%d (%.3f), grand %d/%d (%.3f)%n",
                total.tichuMade, total.tichuCalls, total.tichuRate(),
                total.grandMade, total.grandCalls, total.grandRate());
        calibration.print();
    }

    /**
     * 자가대전 검증 표본 600매치 — 사전 등록 목표(티츄 ≥ 0.70, 그랜드 ≥ 0.55)를 여기서 단언한다.
     * 문턱 구간 표는 일부러 내지 않는다(검증 표본으로 문턱을 다시 고르지 않도록).
     */
    @Test
    void self_play_holdout_meets_declaration_targets() {
        var stats = selfPlay(null, HOLDOUT_FIRST_SEED, HOLDOUT_MATCHES);
        System.out.printf("[D-118 eval] holdout seeds %d~%d: %s%n", HOLDOUT_FIRST_SEED,
                HOLDOUT_FIRST_SEED + HOLDOUT_MATCHES - 1, summary(stats));
        double[] ci = wilson(stats.tichuMade, stats.tichuCalls);
        System.out.printf("[D-118 eval] holdout tichu 95%% CI %.3f–%.3f%n", ci[0], ci[1]);

        assertThat(stats.fellBack).isZero();
        assertThat(stats.tichuRate()).isGreaterThanOrEqualTo(TICHU_TARGET);
        if (stats.grandCalls < GRAND_MIN_SAMPLE) {
            System.out.printf("[D-118 eval] grand 표본 부족 (%d < %d) — 목표 단언 생략%n",
                    stats.grandCalls, GRAND_MIN_SAMPLE);
        } else {
            assertThat(stats.grandRate()).isGreaterThanOrEqualTo(GRAND_TARGET);
        }
    }

    /** @param calibration null 이면 문턱 구간을 기록하지 않는다. */
    private static TichuBotArena.Stats selfPlay(Calibration calibration, long firstSeed, int matches) {
        var arena = new TichuBotArena().observe(calibration);
        var stats = new TichuBotArena.Stats();
        var seats = new TichuBotArena.Seat[] {TichuBotArena.heuristic(), TichuBotArena.heuristic(),
                TichuBotArena.heuristic(), TichuBotArena.heuristic()};
        for (long deck = firstSeed; deck < firstSeed + matches; deck++) {
            arena.playMatch(seats, deck, stats);
        }
        return stats;
    }

    private static String summary(TichuBotArena.Stats stats) {
        return String.format("%d rounds, tichu %d/%d (%.3f), grand %d/%d (%.3f),"
                        + " partner-out-first %d, fellBack %d",
                stats.rounds, stats.tichuMade, stats.tichuCalls, stats.tichuRate(),
                stats.grandMade, stats.grandCalls, stats.grandRate(),
                stats.partnerOutFirstWhileDeclared, stats.fellBack);
    }

    /** 기능 끄기 비교: 전부 켠 휴리스틱 vs 하나 끈 휴리스틱, 듀플리케이트 500딜. */
    @Test
    void ablation_table() {
        List<Map.Entry<String, HeuristicBotPolicy.Tuning>> off = List.of(
                Map.entry("선언 끔", new HeuristicBotPolicy.Tuning(false, true, true, true)),
                Map.entry("파트너 가드 끔", new HeuristicBotPolicy.Tuning(true, false, true, true)),
                Map.entry("폭탄 끔", new HeuristicBotPolicy.Tuning(true, true, false, true)),
                Map.entry("패스 휴리스틱 끔", new HeuristicBotPolicy.Tuning(true, true, true, false)));
        System.out.println("[D-118 eval] ablation (full vs feature-off, 500 duplicate deals):");
        for (var e : off) {
            var arena = new TichuBotArena();
            var stats = new TichuBotArena.Stats();
            var deltas = HeuristicBotSimulationTest.duplicateDeltas(arena, stats,
                    TichuBotArena.heuristic(), TichuBotArena.heuristic(e.getValue()), 40_000, 500);
            var sum = HeuristicBotSimulationTest.DuplicateSummary.of(deltas);
            System.out.printf("  %-12s %s%n", e.getKey(), sum);
        }
    }

    // ---------- 선언 보정 로그 ----------

    /**
     * 휴리스틱이 선언하는 순간의 손패 지표(그랜드=power8, 티츄=controls·비컨트롤 묶음)를 남기고
     * 라운드 끝에 성패를 붙인다.
     */
    private static final class Calibration implements TichuBotArena.Observer {
        private final List<int[]> pending = new ArrayList<>();   // seat, kind, a, b
        private final Map<String, int[]> buckets = new TreeMap<>();

        @Override
        public void beforeAct(TichuState state, int seat, TichuBotArena.Seat policy) {
            if (!policy.heuristic()) return;
            TichuAction action = HeuristicBotPolicy.choose(state, seat);
            long hand = HandPlanner.mask(state.players().get(seat).hand());
            if (action instanceof TichuAction.DeclareGrandTichu) {
                int power = Long.bitCount(hand & (HandPlanner.DRAGON | HandPlanner.PHOENIX))
                        + HandPlanner.count(hand, 14);
                for (int r = 2; r <= 14; r++) {
                    if (HandPlanner.count(hand, r) == 4) power += 2;
                }
                pending.add(new int[] {seat, 0, power, 0});
            } else if (action instanceof TichuAction.DeclareTichu) {
                var plan = HandPlanner.plan(hand);
                int kind = state instanceof TichuState.Dealing ? 1 : 2;
                pending.add(new int[] {seat, kind, plan.controls(), plan.size() - plan.controls()});
            }
        }

        @Override
        public void roundEnded(TichuState.RoundEnd end) {
            for (int[] p : pending) {
                boolean made = end.players().get(p[0]).finishedOrder() == 1;
                String key = switch (p[1]) {
                    case 0 -> String.format("grand power8=%d", p[2]);
                    case 1 -> String.format("tichu(패스 전) controls=%d 비컨트롤=%d", p[2], p[3]);
                    default -> String.format("tichu(패스 후) controls=%d 비컨트롤=%d", p[2], p[3]);
                };
                int[] b = buckets.computeIfAbsent(key, k -> new int[2]);
                b[0]++;
                if (made) b[1]++;
            }
            pending.clear();
        }

        void print() {
            System.out.println("[D-118 eval] declaration calibration (calls / made / rate):");
            for (var e : buckets.entrySet()) {
                int[] b = e.getValue();
                System.out.printf("  %-48s %4d / %4d / %.3f%n", e.getKey(), b[0], b[1],
                        (double) b[1] / b[0]);
            }
        }
    }

    /** 이항 비율의 Wilson 95% 구간. */
    private static double[] wilson(int k, int n) {
        double z = 1.96;
        double p = (double) k / n;
        double denom = 1 + z * z / n;
        double center = (p + z * z / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[] {center - half, center + half};
    }
}
