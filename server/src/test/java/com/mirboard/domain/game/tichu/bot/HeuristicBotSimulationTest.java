package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.state.PassCardsSelection;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-118 기본 스위트 평가 — Docker 없는 순수 엔진 시뮬레이션({@link TichuBotArena}).
 *
 * <p>통과 기준은 <b>구현 전에 사전 등록</b>했고 구현 후에 낮추지 않는다
 * ({@code docs/plans/tichu-bot-heuristic.md}). 시드가 고정이라 테스트 자체는 결정적이며,
 * 아래 통계는 "이 고정 표본이 증거로 충분한가"의 근거다.
 *
 * <p>무거운 표본(랜덤 1000매치·그리디 1000딜·자가대전 보정 800매치·검증 600매치·기능 끄기
 * 비교)은 {@link HeuristicBotEvaluationTest} 로 분리했다({@code MIRBOARD_BOT_EVAL=1}).
 */
class HeuristicBotSimulationTest {

    /**
     * 랜덤 상대(이전 운영 봇) 100매치 ≥ 75승. 귀무(p=0.5)에서 P(X≥75)=2.8e-7, 같은 딜을 쓰는
     * 두 매치를 완전 상관으로 봐 유효 n=50 으로 줄여도 P(X≥38)=1.5e-4.
     */
    @Test
    void heuristic_team_beats_random_team_in_full_matches() {
        var arena = new TichuBotArena();
        var stats = new TichuBotArena.Stats();
        int wins = 0;
        int matches = 0;
        long scoreDiff = 0;
        int rounds = 0;
        for (long deck = 0; deck < 50; deck++) {
            for (int swap = 0; swap < 2; swap++) {
                Team heuristicTeam = swap == 0 ? Team.A : Team.B;
                var seats = new TichuBotArena.Seat[4];
                for (int s = 0; s < 4; s++) {
                    seats[s] = Team.ofSeat(s) == heuristicTeam
                            ? TichuBotArena.heuristic()
                            : TichuBotArena.random(deck * 10 + s);
                }
                var result = arena.playMatch(seats, deck, stats);
                matches++;
                rounds += result.rounds();
                if (result.winner() == heuristicTeam) wins++;
                int h = heuristicTeam == Team.A ? result.cumulativeA() : result.cumulativeB();
                int r = heuristicTeam == Team.A ? result.cumulativeB() : result.cumulativeA();
                scoreDiff += h - r;
            }
        }
        System.out.printf("[D-118] vs random: %d/%d wins, avg rounds %.2f, score diff/round %.1f,"
                        + " double victories %d/%d rounds, fellBack %d%n",
                wins, matches, (double) rounds / matches, (double) scoreDiff / rounds,
                stats.doubleVictories, stats.rounds, stats.fellBack);

        assertThat(stats.fellBack).isZero();
        assertThat(wins).isGreaterThanOrEqualTo(75);
    }

    /**
     * 그리디 기준선 상대 듀플리케이트 200딜 × 2테이블. 딜별 Δ = 두 테이블의 (휴리스틱 팀 − 그리디
     * 팀) 라운드 점수 합. 동률을 뺀 n 딜 중 Δ>0 인 딜 수 ≥ n/2 + 1.1632·√n (정규근사 단측
     * p&lt;0.01) ∧ 평균 Δ > 0.
     */
    @Test
    void heuristic_beats_greedy_baseline_in_duplicate_deals() {
        var arena = new TichuBotArena();
        var stats = new TichuBotArena.Stats();
        var h = TichuBotArena.heuristic();
        var g = TichuBotArena.greedy();
        var deltas = duplicateDeltas(arena, stats, h, g, 0, 200);

        DuplicateSummary sum = DuplicateSummary.of(deltas);
        System.out.println("[D-118] vs greedy: " + sum + ", fellBack " + stats.fellBack);

        assertThat(stats.fellBack).isZero();
        assertThat((double) sum.positive()).isGreaterThanOrEqualTo(sum.threshold());
        assertThat(sum.mean()).isPositive();
    }

    /**
     * 휴리스틱×4 자가대전 20매치: 예외·스톨 없이 끝나고(하네스가 매 액션 validate·invariant·
     * 상한을 검사) fellBack 0. 티츄 선언 ≥ 20건 ∧ 성공률 ≥ 0.60.
     */
    @Test
    void self_play_full_matches_are_legal_and_declarations_pay() {
        var arena = new TichuBotArena();
        var stats = new TichuBotArena.Stats();
        var seats = new TichuBotArena.Seat[] {TichuBotArena.heuristic(), TichuBotArena.heuristic(),
                TichuBotArena.heuristic(), TichuBotArena.heuristic()};
        int rounds = 0;
        for (long deck = 1000; deck < 1020; deck++) {
            rounds += arena.playMatch(seats, deck, stats).rounds();
        }
        System.out.printf("[D-118] self-play: 20 matches, %d rounds, tichu %d/%d (%.2f),"
                        + " grand %d/%d, partner-out-first %d, fellBack %d, action-log hash %016x%n",
                rounds, stats.tichuMade, stats.tichuCalls, stats.tichuRate(),
                stats.grandMade, stats.grandCalls, stats.partnerOutFirstWhileDeclared,
                stats.fellBack, stats.actionLogHash);

        assertThat(stats.fellBack).isZero();
        assertThat(stats.tichuCalls).isGreaterThanOrEqualTo(20);
        assertThat(stats.tichuRate()).isGreaterThanOrEqualTo(0.60);
    }

    /** 팀마다 휴리스틱 + 랜덤/그리디를 섞어도 합법성·무스톨·invariant·fellBack 0. */
    @Test
    void mixed_seating_rounds_are_legal() {
        var arena = new TichuBotArena();
        var stats = new TichuBotArena.Stats();
        for (int i = 0; i < 50; i++) {
            var withRandom = new TichuBotArena.Seat[] {TichuBotArena.heuristic(),
                    TichuBotArena.heuristic(), TichuBotArena.random(i * 2L),
                    TichuBotArena.random(i * 2L + 1)};
            arena.playDeal(withRandom, 5000 + i, stats);
            var withGreedy = new TichuBotArena.Seat[] {TichuBotArena.greedy(),
                    TichuBotArena.heuristic(), TichuBotArena.heuristic(), TichuBotArena.greedy()};
            arena.playDeal(withGreedy, 6000 + i, stats);
        }
        assertThat(stats.rounds).isEqualTo(100);
        assertThat(stats.fellBack).isZero();
    }

    /**
     * 표본 결정마다 숨김 정보(본인 외 손패 — 장수 보존 재분배, Dealing(8) 은 내 몫 포함
     * reservedSecondHalf 까지 풀에 넣음 —, 남의 Passing.submitted)와 입력 순서(본인 손패 리스트,
     * legal 목록)를 바꿔도 propose 결과가 같다.
     */
    @Test
    void decisions_ignore_hidden_information_and_input_order() {
        var policy = new HeuristicBotPolicy(HeuristicBotPolicy.Tuning.DEFAULT);
        Random rng = new Random(42);
        int[] checked = {0};
        int[] tick = {0};
        var arena = new TichuBotArena().observe((state, seat, p) -> {
            if (!p.heuristic() || tick[0]++ % 4 != 0) return;
            var legal = LegalActionEnumerator.enumerateFull(state, seat);
            var expected = policy.propose(BotView.of(state, seat), legal);
            for (int k = 0; k < 3; k++) {
                TichuState scrambled = scramble(state, seat, rng);
                List<com.mirboard.domain.game.tichu.action.TichuAction> legal2 =
                        new ArrayList<>(LegalActionEnumerator.enumerateFull(scrambled, seat));
                Collections.shuffle(legal2, rng);
                assertThat(policy.propose(BotView.of(scrambled, seat), legal2))
                        .as("seat %d in %s", seat, state.getClass().getSimpleName())
                        .isEqualTo(expected);
            }
            checked[0]++;
        });
        var stats = new TichuBotArena.Stats();
        var seats = new TichuBotArena.Seat[] {TichuBotArena.heuristic(), TichuBotArena.heuristic(),
                TichuBotArena.heuristic(), TichuBotArena.heuristic()};
        for (int i = 0; i < 16; i++) {
            arena.playDeal(seats, 7000 + i, stats);
        }
        assertThat(checked[0]).isGreaterThan(200);
    }

    /**
     * 결정당 후보 ≤ 256 (결정적). 워밍업 1000결정 이후 평균 &lt; 0.5ms, p99 &lt; 5ms. 최대값은
     * 출력만 한다 — 벽시계 최대값 단언은 GC·JIT 로 느린 CI 에서 플레이크가 난다(RoomActionLock
     * TTL 2s 대비 여유 기록용).
     */
    @Test
    void decision_cost_within_budget() {
        int[] maxCandidates = {0};
        var arena = new TichuBotArena().observe((state, seat, p) -> {
            if (!p.heuristic()) return;
            long distinct = LegalActionEnumerator.enumerateFull(state, seat).stream()
                    .filter(a -> a instanceof com.mirboard.domain.game.tichu.action.TichuAction.PlayCard)
                    .map(a -> HandPlanner.mask(
                            ((com.mirboard.domain.game.tichu.action.TichuAction.PlayCard) a).cards()))
                    .distinct().count();
            maxCandidates[0] = (int) Math.max(maxCandidates[0], distinct);
        });
        var stats = new TichuBotArena.Stats();
        var seats = new TichuBotArena.Seat[] {TichuBotArena.heuristic(), TichuBotArena.heuristic(),
                TichuBotArena.heuristic(), TichuBotArena.heuristic()};
        for (int i = 0; i < 60; i++) {
            arena.playDeal(seats, 8000 + i, stats);
        }
        List<Long> measured = new ArrayList<>(
                stats.heuristicNanos.subList(1000, stats.heuristicNanos.size()));
        Collections.sort(measured);
        double avgMs = measured.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p99Ms = measured.get((int) Math.floor(measured.size() * 0.99)) / 1e6;
        double maxMs = measured.get(measured.size() - 1) / 1e6;
        System.out.printf("[D-118] decision cost: n=%d avg %.3fms p99 %.3fms max %.3fms,"
                        + " max candidates %d%n",
                measured.size(), avgMs, p99Ms, maxMs, maxCandidates[0]);

        assertThat(measured.size()).isGreaterThan(1000);
        assertThat(maxCandidates[0]).isLessThanOrEqualTo(256);
        assertThat(avgMs).isLessThan(0.5);
        assertThat(p99Ms).isLessThan(5.0);
    }

    // ---------- 공용 ----------

    /** 딜별 Δ. 테이블 1 = 휴리스틱이 A(0·2), 테이블 2 = 휴리스틱이 B(1·3). */
    static List<Integer> duplicateDeltas(TichuBotArena arena, TichuBotArena.Stats stats,
                                         TichuBotArena.Seat heuristic, TichuBotArena.Seat other,
                                         long firstDeal, int deals) {
        List<Integer> deltas = new ArrayList<>(deals);
        var table1 = new TichuBotArena.Seat[] {heuristic, other, heuristic, other};
        var table2 = new TichuBotArena.Seat[] {other, heuristic, other, heuristic};
        for (long deal = firstDeal; deal < firstDeal + deals; deal++) {
            var r1 = arena.playDeal(table1, deal, stats);
            var r2 = arena.playDeal(table2, deal, stats);
            deltas.add((r1.teamScore(Team.A) - r1.teamScore(Team.B))
                    + (r2.teamScore(Team.B) - r2.teamScore(Team.A)));
        }
        return deltas;
    }

    /**
     * 듀플리케이트 요약. threshold = n/2 + 1.1632·√n (n = 동률 제외 딜 수, z₀.₉₉/2).
     */
    record DuplicateSummary(int deals, int nonTied, int positive, double mean, double ciHalf) {
        static DuplicateSummary of(List<Integer> deltas) {
            int nonTied = 0;
            int positive = 0;
            double sum = 0;
            for (int d : deltas) {
                if (d != 0) nonTied++;
                if (d > 0) positive++;
                sum += d;
            }
            double mean = sum / deltas.size();
            double var = 0;
            for (int d : deltas) var += (d - mean) * (d - mean);
            double sd = Math.sqrt(var / Math.max(1, deltas.size() - 1));
            return new DuplicateSummary(deltas.size(), nonTied, positive, mean,
                    1.96 * sd / Math.sqrt(deltas.size()));
        }

        double threshold() {
            return nonTied / 2.0 + 1.1632 * Math.sqrt(nonTied);
        }

        double share() {
            return nonTied == 0 ? Double.NaN : (double) positive / nonTied;
        }

        @Override
        public String toString() {
            return String.format("%d deals, Δ>0 %d/%d (share %.3f, need ≥ %.1f), mean Δ %.1f ± %.1f",
                    deals, positive, nonTied, share(), threshold(), mean, ciHalf);
        }
    }

    /** 내 손패·공개 정보는 그대로 두고 숨김 정보만 바꾼다. */
    static TichuState scramble(TichuState state, int seat, Random rng) {
        List<PlayerState> players = state.players();
        List<Card> pool = new ArrayList<>();
        for (PlayerState p : players) {
            if (p.seat() != seat) pool.addAll(p.hand());
        }
        Map<Integer, List<Card>> reserved = new LinkedHashMap<>();
        if (state instanceof TichuState.Dealing d) {
            for (int s = 0; s < 4; s++) {
                pool.addAll(d.reservedSecondHalf().getOrDefault(s, List.of()));
            }
        }
        Collections.shuffle(pool, rng);
        int cursor = 0;
        List<PlayerState> next = new ArrayList<>();
        for (PlayerState p : players) {
            List<Card> hand;
            if (p.seat() == seat) {
                hand = new ArrayList<>(p.hand());
                Collections.shuffle(hand, rng);
            } else {
                hand = new ArrayList<>(pool.subList(cursor, cursor + p.handSize()));
                cursor += p.handSize();
            }
            next.add(new PlayerState(p.seat(), hand, p.declaration(), p.finishedOrder(),
                    p.tricksWon()));
        }
        return switch (state) {
            case TichuState.Dealing d -> {
                for (int s = 0; s < 4; s++) {
                    int size = d.reservedSecondHalf().getOrDefault(s, List.of()).size();
                    reserved.put(s, new ArrayList<>(pool.subList(cursor, cursor + size)));
                    cursor += size;
                }
                yield new TichuState.Dealing(next, d.phaseCardCount(), d.ready(), reserved);
            }
            case TichuState.Passing p -> {
                Map<Integer, PassCardsSelection> submitted = new LinkedHashMap<>();
                for (var e : p.submitted().entrySet()) {
                    List<Card> h = new ArrayList<>(next.get(e.getKey()).hand());
                    Collections.shuffle(h, rng);
                    submitted.put(e.getKey(), e.getKey() == seat ? e.getValue()
                            : new PassCardsSelection(h.get(0), h.get(1), h.get(2)));
                }
                yield new TichuState.Passing(next, submitted);
            }
            case TichuState.Playing pl -> new TichuState.Playing(next, pl.trick(), pl.firstFinisher());
            case TichuState.RoundEnd re -> re;
        };
    }
}
