package com.mirboard.domain.game.skullking.scoring;

import com.mirboard.domain.game.skullking.state.PlayerState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 라운드 점수 (`docs/rules-skullking.md` §10).
 *
 * <table>
 *   <caption>§10 표</caption>
 *   <tr><th>경우</th><th>점수</th></tr>
 *   <tr><td>{@code bid > 0 && bid == won}</td><td>{@code +won × 20} + 보너스</td></tr>
 *   <tr><td>{@code bid > 0 && bid != won}</td><td>{@code -|bid-won| × 10}</td></tr>
 *   <tr><td>{@code bid == 0 && won == 0}</td><td>{@code +R × 10} + 보너스</td></tr>
 *   <tr><td>{@code bid == 0 && won != 0}</td><td>{@code -R × 10}</td></tr>
 * </table>
 *
 * <p><b>함정 두 개 (명세 §10, 둘 다 원문 미규정).</b>
 * <ol>
 *   <li>0 예측 <b>실패</b>는 일반칙(차이 × 10)이 아니라 <b>정액</b> {@code R × 10} 감점이다
 *       (§13-⑬). 더 좁은 조건인 0 예측 특칙이 일반칙을 대체한다 — 두 규칙을 합산하는
 *       구현은 원문 어느 문장으로도 지지되지 않는다.</li>
 *   <li>0 예측 점수의 {@code R} 은 <b>라운드 번호</b>지 트릭 수가 아니다 (§13-⑫).
 *       8인 라운드 9·10 에서만 갈리는데, 손패가 둘 다 8장인데 0 예측 성공 점수는 90 / 100
 *       으로 갈린다. 어색하지만 원문 문면을 따른 결과다.</li>
 * </ol>
 */
public final class RoundScorer {

    /** 적중 시 승수당 점수. */
    public static final int PER_TRICK = 20;

    /** 실패 시 차이당 감점, 그리고 0 예측 시 라운드당 가감점. */
    public static final int PER_STEP = 10;

    private RoundScorer() {
    }

    /** 한 좌석의 라운드 점수. 보너스는 적중했을 때만 붙는다 (§11). */
    public static RoundScore score(PlayerState player, int roundNumber) {
        int bid = player.bid();
        int won = player.tricksWonCount();
        int base = baseScore(bid, won, roundNumber);
        int bonus = bid == won ? BonusCalculator.bonusFor(player.tricksWon()) : 0;
        return new RoundScore(bid, won, base, bonus);
    }

    /** 좌석 → 라운드 점수. 좌석 순서를 보존한다. */
    public static Map<Integer, RoundScore> scoreAll(List<PlayerState> players, int roundNumber) {
        Map<Integer, RoundScore> scores = new LinkedHashMap<>();
        for (PlayerState player : players) {
            scores.put(player.seat(), score(player, roundNumber));
        }
        return Map.copyOf(scores);
    }

    /** §10 표의 기본 점수 (보너스 제외). */
    public static int baseScore(int bid, int won, int roundNumber) {
        if (bid == 0) {
            // 0 예측 특칙이 일반칙을 대체한다 (§13-⑬). 실패해도 차이가 아니라 정액이다.
            return won == 0 ? roundNumber * PER_STEP : -roundNumber * PER_STEP;
        }
        return bid == won
                ? won * PER_TRICK
                : -Math.abs(bid - won) * PER_STEP;
    }
}
