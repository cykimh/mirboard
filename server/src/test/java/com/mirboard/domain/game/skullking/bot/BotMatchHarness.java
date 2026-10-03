package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.SkullKingEngine;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.invariant.SkullKingInvariantChecker;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * D-119 — 봇 강도 평가용 <b>쌍대(paired) 하네스</b> (테스트 전용). 순수
 * {@link SkullKingEngine} 으로 10라운드 매치를 완주시킨다 — Spring·Redis·Docker 0.
 *
 * <p>쌍대 비교가 요점이다. 분배 난수를 {@code (seed, round)} 로, 무작위 봇 난수를 좌석별로
 * 고정하므로 같은 시드에서 한 좌석의 정책만 바꿔 돌리면 <b>같은 10라운드 패</b> 위에서 정책
 * 차이만 남는다(대조군이 같은 패를 받는다). 정책이 분배 난수를 소비하지 않기 때문에
 * 가능한 구성이다.
 *
 * <p>매 액션마다 대기 좌석·합법수 비어 있지 않음, 선택 ∈ 합법수(최약수 포함), 카드 보존
 * 불변식, 라운드당 액션 상한을 확인하고, 매치가 정확히 10라운드를 채웠는지 본다 — 강도
 * 평가가 곧 전함수성 회귀 가드이기도 하다.
 */
final class BotMatchHarness {

    /** 좌석 하나에 앉히는 정책. */
    enum Policy {
        /** D-119 휴리스틱 — 프로덕션 봇과 같은 경로({@code SkullKingBotView} → 정책). */
        HEURISTIC,
        /** 포트 기본값과 같은 합법수 균등 분포 — D-119 이전의 봇. */
        RANDOM,
        /** 0 예측 + 최약수 — 타임아웃·유령 자동조종과 같은 {@code timeoutAction}. */
        WEAKEST
    }

    /**
     * 한 매치의 결과.
     *
     * @param scores  좌석별 최종 누적 점수
     * @param bidHits 좌석별 예측 적중 라운드 수 (10 중)
     */
    record MatchOutcome(int[] scores, int[] bidHits) {
    }

    /**
     * 한 시나리오의 집계. 표준오차는 매치 단위 표본 기준.
     *
     * @param gap     초점 좌석 평균 − 나머지 좌석 평균 (전원 초점이면 초점 평균)
     * @param share   초점 진영이 가져간 승리 몫 — 공동 1위는 인원수로 나눈다
     * @param mean    초점 좌석 평균 점수
     * @param hitRate 초점 좌석의 라운드 예측 적중률
     */
    record Stat(double gap, double gapSe, double share,
                double mean, double meanSe, double hitRate) {

        /** 강도 하한 묶음 — 세 조건 AND. 대조군이 이걸 넘으면 하한이 무의미하다는 뜻. */
        boolean clears(double minGap, double minShare) {
            return gap >= minGap && share >= minShare && mean > 0;
        }
    }

    private BotMatchHarness() {
    }

    /**
     * 시나리오 하나 — 매치 {@code m} 의 시드는 {@code base + 1000·n + m}, 초점 좌석은
     * {@code (m + j) % n} ({@code j < focalCount}) 로 돌려 좌석 위치 효과를 상쇄한다.
     */
    static Stat run(int seatCount, int matches, long base,
                    int focalCount, Policy focal, Policy others) {
        double[] gaps = new double[matches];
        double[] means = new double[matches];
        double share = 0;
        long hits = 0;
        for (int m = 0; m < matches; m++) {
            Policy[] policies = new Policy[seatCount];
            Arrays.fill(policies, others);
            Set<Integer> focalSeats = new HashSet<>();
            for (int j = 0; j < focalCount; j++) {
                int seat = (m + j) % seatCount;
                focalSeats.add(seat);
                policies[seat] = focal;
            }
            MatchOutcome outcome = play(seatCount, base + 1000L * seatCount + m, policies);

            double focalSum = 0;
            double otherSum = 0;
            for (int seat = 0; seat < seatCount; seat++) {
                if (focalSeats.contains(seat)) {
                    focalSum += outcome.scores()[seat];
                    hits += outcome.bidHits()[seat];
                } else {
                    otherSum += outcome.scores()[seat];
                }
            }
            means[m] = focalSum / focalCount;
            gaps[m] = means[m] - (focalCount == seatCount ? 0 : otherSum / (seatCount - focalCount));

            int best = Arrays.stream(outcome.scores()).max().orElseThrow();
            long tied = Arrays.stream(outcome.scores()).filter(s -> s == best).count();
            for (int seat : focalSeats) {
                if (outcome.scores()[seat] == best) {
                    share += 1.0 / tied;
                }
            }
        }
        double hitRate = (double) hits
                / ((long) matches * focalCount * SkullKingMatchState.TOTAL_ROUNDS);
        return new Stat(average(gaps), standardError(gaps), share / matches,
                average(means), standardError(means), hitRate);
    }

    /** 10라운드 매치 하나를 완주시킨다. 대기 좌석이 여럿(입찰)이면 가장 낮은 좌석부터. */
    static MatchOutcome play(int seatCount, long seed, Policy[] policies) {
        List<Long> playerIds = new ArrayList<>();
        for (long i = 0; i < seatCount; i++) {
            playerIds.add(700L + i);
        }
        SkullKingEngine engine = new SkullKingEngine(new GameContext("bot-eval", playerIds));
        Random[] seatRandom = new Random[seatCount];
        for (int seat = 0; seat < seatCount; seat++) {
            seatRandom[seat] = new Random(seed * 31 + 17L * seat + 5);
        }
        int[] bidHits = new int[seatCount];

        SkullKingMatchState match = SkullKingMatchState.initial(
                seatCount, (int) Math.floorMod(seed, (long) seatCount));
        while (!match.isMatchOver()) {
            SkullKingState state = engine.startRound(
                    match, new Random(seed * 1_000_003L + match.roundNumber())).newState();
            SkullKingInvariantChecker.check(state);
            int guard = 0;
            while (!engine.isRoundOver(state)) {
                if (++guard > 5_000) {
                    throw new AssertionError("round did not terminate: " + state.phaseName());
                }
                List<Integer> pending = engine.pendingSeats(state);
                if (pending.isEmpty()) {
                    throw new AssertionError("진행 중인데 대기 좌석이 없다 — 교착");
                }
                int seat = pending.get(0);
                List<SkullKingAction> legal = engine.legalActions(state, seat);
                if (legal.isEmpty()) {
                    throw new AssertionError("좌석 " + seat + " 이 대기 중인데 합법 액션이 없다");
                }
                SkullKingAction action = switch (policies[seat]) {
                    case HEURISTIC -> SkullKingBotPolicy.choose(
                            SkullKingBotView.of(state, seat), legal);
                    case RANDOM -> legal.get(seatRandom[seat].nextInt(legal.size()));
                    case WEAKEST -> engine.timeoutAction(state, seat);
                };
                if (!legal.contains(action)) {
                    throw new AssertionError(policies[seat] + " 정책이 합법수 밖 액션을 냈다: "
                            + action + " (legal=" + legal + ")");
                }
                state = engine.apply(state, seat, action).newState();
                SkullKingInvariantChecker.check(state);
            }

            SkullKingState.RoundEnd ended = (SkullKingState.RoundEnd) state;
            for (Map.Entry<Integer, RoundScore> score : ended.scores().entrySet()) {
                if (score.getValue().bidHit()) {
                    bidHits[score.getKey()]++;
                }
            }
            match = engine.settleRound(ended, match).matchState();
        }
        if (match.roundNumber() != SkullKingMatchState.TOTAL_ROUNDS + 1) {
            throw new AssertionError("10라운드 완주가 아니다: " + match.roundNumber());
        }

        int[] scores = new int[seatCount];
        for (int seat = 0; seat < seatCount; seat++) {
            scores[seat] = match.cumulativeScores().get(seat);
        }
        return new MatchOutcome(scores, bidHits);
    }

    private static double average(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double standardError(double[] values) {
        double mean = average(values);
        double squares = 0;
        for (double v : values) {
            squares += (v - mean) * (v - mean);
        }
        return Math.sqrt(squares / (values.length - 1)) / Math.sqrt(values.length);
    }
}
