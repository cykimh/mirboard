package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.Dealer;
import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import java.util.ArrayList;
import java.util.List;

/**
 * D-230 — 상대가 손패를 보고 얼마를 예측할지의 어림. 두 곳에 쓴다.
 * <ul>
 *   <li><b>입찰 중</b>(남의 예측 비공개): 뽑은 손패로 그 좌석의 예측을 어림해 굴린다.</li>
 *   <li><b>플레이 중</b>(예측 공개): 뽑은 손패가 공개된 예측과 얼마나 맞는지로 표본에 무게를 준다
 *       — 5를 예측한 좌석은 센 카드를 들고 있을 가능성이 높다.</li>
 * </ul>
 *
 * <p>어림 = 카드별 기대 승수 표의 합(D-119 입찰식과 같은 식 — 리드 승률 × 리드 보정을 미공개
 * 풀 = 덱에서 그 카드만 뺀 것으로 미리 계산) + 그 좌석의 <b>예측 버릇</b>. 버릇은 지난 라운드
 * 예측이 "그 장수 손패의 평균 어림"에서 평균 얼마나 벗어났는지를 사전 1라운드 0 으로 줄인
 * 값이다. 0 만 부르는 상대(최약수 자동조종 같은)는 몇 라운드 만에 큰 음수 버릇이 붙는다.
 */
final class OpponentModel {

    /** 버릇 평균의 사전 가중 — 라운드 하나 분량의 0 을 섞는다. */
    static final double HABIT_PRIOR = 1.0;

    /** [인원][카드 코드] → 그 카드 한 장의 기대 승수. */
    private static final double[][] WIN_VALUE = new double[9][CardCode.CODES];
    /** [인원] → 덱 70장 평균 기대 승수 (버릇의 기준). */
    private static final double[] MEAN_VALUE = new double[9];
    /** [뒤에 낼 사람 수 1~7][판정 코드] → 지금 이기는 그 카드가 끝까지 버틸 확률의 어림. */
    private static final double[][] HOLD = new double[8][CardCode.CODES];

    static {
        List<SkullCard> deck = Deck.unshuffled().cards();
        for (int n = 2; n <= 8; n++) {
            double[] row = WIN_VALUE[n];
            boolean[] done = new boolean[CardCode.CODES];
            double sum = 0;
            for (SkullCard card : deck) {
                int code = CardCode.of(card);
                if (!done[code]) {
                    List<SkullCard> pool = new ArrayList<>(deck);
                    pool.remove(card);
                    PlayedCard lead = card.is(SpecialKind.TIGRESS)
                            ? PlayedCard.tigress(0, TigressMode.PIRATE)
                            : PlayedCard.of(0, card);
                    row[code] = TrickOdds.winProbability(List.of(), lead, n - 1, pool)
                            * leadFactor(card, n);
                    done[code] = true;
                }
                sum += row[code];
            }
            MEAN_VALUE[n] = sum / deck.size();
        }
        for (int after = 1; after <= 7; after++) {
            for (SkullCard card : deck) {
                if (card.is(SpecialKind.TIGRESS)) {
                    continue;   // 판정 코드로는 해적·탈출 — 표에 따로 둘 필요가 없다
                }
                List<SkullCard> pool = new ArrayList<>(deck);
                pool.remove(card);
                HOLD[after][CardCode.of(card)] = TrickOdds.winProbability(
                        List.of(), PlayedCard.of(0, card), after, pool);
            }
        }
    }

    /** 지금 이기는 판정 코드 {@code played} 가 뒤의 {@code after} 명을 버틸 확률의 어림. */
    static double hold(int after, int played) {
        return HOLD[after][played];
    }

    private OpponentModel() {
    }

    /** D-119 입찰식의 리드 보정과 같다 (`OddsHeuristic`). */
    private static double leadFactor(SkullCard card, int n) {
        if (!card.isSuit()) {
            return 1.0;
        }
        double kept = card.isTrump() ? OddsHeuristic.FOLLOW_BLACK : OddsHeuristic.FOLLOW_PLAIN;
        return 1.0 / n + (n - 1.0) / n * kept;
    }

    static double value(int n, int code) {
        return WIN_VALUE[n][code];
    }

    /** 손패 코드 배열의 기대 승수 합. */
    static double handValue(int n, int[] codes, int len) {
        double sum = 0;
        for (int i = 0; i < len; i++) {
            sum += WIN_VALUE[n][codes[i]];
        }
        return sum;
    }

    /**
     * 좌석별 예측 버릇 — 지난 라운드 예측 − (그 라운드 손패 장수 × 평균 기대 승수) 의 평균을
     * {@link #HABIT_PRIOR} 라운드 분량의 0 으로 줄인 값.
     */
    static double[] habits(List<List<Integer>> pastBids, int n) {
        double[] sum = new double[n];
        int[] count = new int[n];
        for (int r = 0; r < pastBids.size(); r++) {
            List<Integer> bids = pastBids.get(r);
            int handSize = Dealer.handSize(r + 1, n);
            for (int s = 0; s < n && s < bids.size(); s++) {
                int bid = bids.get(s);
                if (bid != PlayerState.NO_BID) {
                    sum[s] += bid - handSize * MEAN_VALUE[n];
                    count[s]++;
                }
            }
        }
        double[] habit = new double[n];
        for (int s = 0; s < n; s++) {
            habit[s] = sum[s] / (count[s] + HABIT_PRIOR);
        }
        return habit;
    }

    /** 손패로 어림한 예측 — 0 ~ handSize 정수. */
    static int predictBid(int n, int[] codes, int len, double habit, int handSize) {
        long rounded = Math.round(handValue(n, codes, len) + habit);
        return (int) Math.max(0, Math.min(handSize, rounded));
    }
}
