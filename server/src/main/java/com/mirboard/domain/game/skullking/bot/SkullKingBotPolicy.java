package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.action.SkullKingAction.PlaceBid;
import com.mirboard.domain.game.skullking.action.SkullKingAction.PlayCard;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import java.util.List;

/**
 * D-119 — 스컬킹 봇 휴리스틱 (결정적, 전함수). D-102 보류 ② 의 균등 무작위 봇을 대체한다.
 *
 * <p><b>입찰</b>: 손패 카드별 "리드했을 때의 승률 × 리드 보정"을 합한 기대 승수를 반올림한다.
 * <br><b>플레이</b>: 남은 필요 승수가 있으면 "이길 만한 카드 중 가장 싼 것", 없으면 "질 만한
 * 카드 중 들고 있기 가장 위험한 것"을 낸다. 승률은 {@link TrickOdds} 가 낸다.
 *
 * <p><b>전함수다.</b> 합법수가 비면 null, 1개면 그 원소를 즉시 돌려주고, 모든 분기는 비지
 * 않은 합법수 위의 최소/최대로 끝나므로 반환값은 항상 합법수의 원소다. {@code Random} 을
 * 쓰지 않으며 동률은 합법수 순서상 앞선 것으로 깬다. 그래도 정책이 실패하면 어댑터가
 * {@code timeoutAction} 으로 떨어진다 ({@code SkullKingGameEngine#chooseBotAction}).
 *
 * <p>상수 5개는 무작위 상대를 기준으로 평가 시드와 다른 시드 계열에서 정했다. 손실 문턱
 * 스윕(0.15/0.25/0.35)에서 지표가 거의 평탄해 과적합 위험은 낮다. 사람 상대 강도는
 * 측정하지 않았다 (D-119).
 */
public final class SkullKingBotPolicy {

    /** 필요 승수가 남았을 때 "이길 만한" 카드의 승률 문턱 — 이 이상이면 가장 싼 것을 쓴다. */
    static final double LIKELY_WIN = 0.6;

    /** 이길 만한 카드가 없을 때 그래도 걸어 볼 최소 승률 — 이하면 이기기를 포기하고 버린다. */
    static final double WORTH_A_TRY = 0.15;

    /** 예측을 채웠을 때 "질 만한" 카드의 승률 문턱 — 이 중 가장 위험한 카드를 털어낸다. */
    static final double LIKELY_LOSE = 0.2;

    /**
     * 입찰 리드 보정 — 비검정 색상 카드. 리드 승률은 내가 리드할 때(확률 1/n)만 그대로
     * 통하고, 남이 리드하면 오프수트가 되어 지기 쉽다. 그 경우 남는 비율.
     */
    static final double FOLLOW_PLAIN = 0.3;

    /** 입찰 리드 보정 — 검정. 으뜸패라 오프수트여도 이기므로 비검정보다 훨씬 덜 깎인다. */
    static final double FOLLOW_BLACK = 0.75;

    private SkullKingBotPolicy() {
    }

    /**
     * @param view  정보 경계 — 공개된 정보만 담긴 뷰
     * @param legal 엔진이 계산한 합법수 (follow 의무 반영)
     * @return {@code legal} 의 원소. {@code legal} 이 비면 null
     */
    public static SkullKingAction choose(SkullKingBotView view, List<SkullKingAction> legal) {
        if (legal.isEmpty()) {
            return null;
        }
        if (legal.size() == 1) {
            return legal.get(0);
        }
        return view.bidding() ? chooseBid(view, legal) : choosePlay(view, legal);
    }

    // ---------- 입찰 ----------

    private static SkullKingAction chooseBid(SkullKingBotView view, List<SkullKingAction> legal) {
        int n = view.seatCount();
        List<SkullCard> pool = view.unseenCards();
        double expected = 0;
        for (SkullCard card : view.hand()) {
            PlayedCard lead = card.is(SpecialKind.TIGRESS)
                    ? PlayedCard.tigress(view.seat(), TigressMode.PIRATE)
                    : PlayedCard.of(view.seat(), card);
            expected += TrickOdds.winProbability(List.of(), lead, n - 1, pool)
                    * leadFactor(card, n);
        }
        int target = Math.max(0, Math.min(view.hand().size(), (int) Math.floor(expected + 0.5)));

        SkullKingAction best = legal.get(0);
        int bestDistance = Integer.MAX_VALUE;
        for (SkullKingAction action : legal) {
            if (action instanceof PlaceBid bid && Math.abs(bid.bid() - target) < bestDistance) {
                bestDistance = Math.abs(bid.bid() - target);
                best = action;
            }
        }
        return best;
    }

    /** 리드 승률이 실제 기대 승수로 이어지는 비율. 특수 카드는 리드 여부와 무관하다. */
    private static double leadFactor(SkullCard card, int seatCount) {
        if (!card.isSuit()) {
            return 1.0;
        }
        double kept = card.isTrump() ? FOLLOW_BLACK : FOLLOW_PLAIN;
        return 1.0 / seatCount + (seatCount - 1.0) / seatCount * kept;
    }

    // ---------- 플레이 ----------

    private static SkullKingAction choosePlay(SkullKingBotView view, List<SkullKingAction> legal) {
        int need = view.ownBid() - view.ownTricksWon();
        int after = view.playersAfterMe();
        List<SkullCard> pool = view.unseenCards();

        int size = legal.size();
        PlayCard[] plays = new PlayCard[size];
        double[] odds = new double[size];
        for (int i = 0; i < size; i++) {
            if (legal.get(i) instanceof PlayCard play) {
                plays[i] = play;
                odds[i] = TrickOdds.winProbability(view.currentTrick(),
                        new PlayedCard(view.seat(), play.card(), play.declaredAs()), after, pool);
            }
        }
        int pick = need > 0 ? pickToWin(plays, odds) : pickToLose(plays, odds);
        return pick >= 0 ? legal.get(pick) : legal.get(0);
    }

    /** 이겨야 한다: 확실한 것 중 가장 싼 것 → 가장 승산 있는 것 → 버림 순서상 가장 싼 것. */
    private static int pickToWin(PlayCard[] plays, double[] odds) {
        int pick = -1;
        for (int i = 0; i < plays.length; i++) {
            if (plays[i] != null && odds[i] >= LIKELY_WIN
                    && (pick < 0 || spendCost(plays[i]) < spendCost(plays[pick]))) {
                pick = i;
            }
        }
        if (pick >= 0) {
            return pick;
        }
        for (int i = 0; i < plays.length; i++) {
            if (plays[i] != null && odds[i] > WORTH_A_TRY
                    && (pick < 0 || odds[i] > odds[pick]
                        || (odds[i] == odds[pick] && spendCost(plays[i]) < spendCost(plays[pick])))) {
                pick = i;
            }
        }
        if (pick >= 0) {
            return pick;
        }
        // 승산이 없다 — 가장 싼 카드로 이번 트릭을 버린다. 탈출(예측을 채운 뒤 확실히 지는
        // 수단)과 티그리스(선언을 골라 쓰는 카드)는 마지막까지 아낀다.
        for (int i = 0; i < plays.length; i++) {
            if (plays[i] != null && (pick < 0 || discardKey(plays[i]) < discardKey(plays[pick]))) {
                pick = i;
            }
        }
        return pick;
    }

    /** 져야 한다: 질 만한 것 중 가장 위험한 것 → 없으면 승률이 가장 낮은 것. */
    private static int pickToLose(PlayCard[] plays, double[] odds) {
        int pick = -1;
        for (int i = 0; i < plays.length; i++) {
            if (plays[i] != null && odds[i] <= LIKELY_LOSE
                    && (pick < 0 || liability(plays[i]) > liability(plays[pick]))) {
                pick = i;
            }
        }
        if (pick >= 0) {
            return pick;
        }
        for (int i = 0; i < plays.length; i++) {
            if (plays[i] != null
                    && (pick < 0 || odds[i] < odds[pick]
                        || (odds[i] == odds[pick] && liability(plays[i]) > liability(plays[pick])))) {
                pick = i;
            }
        }
        return pick;
    }

    // ---------- 카드 서열 ----------

    private static boolean escapeLike(PlayCard play) {
        return play.card().is(SpecialKind.ESCAPE) || play.declaredAs() == TigressMode.ESCAPE;
    }

    /**
     * 이기는 데 쓰기 아까운 정도 — 탈출류 0 &lt; 비검정 100+r &lt; 검정 200+r &lt; 인어 300
     * &lt; 해적 400 &lt; 티그리스(해적 선언) 450 &lt; 스컬킹 500. 티그리스가 해적보다 비싼
     * 것은 선언을 고를 수 있는 유연성 때문이다.
     */
    static int spendCost(PlayCard play) {
        if (escapeLike(play)) {
            return 0;
        }
        SkullCard card = play.card();
        if (card.isSuit()) {
            return card.isTrump() ? 200 + card.rank() : 100 + card.rank();
        }
        return switch (card.special()) {
            case MERMAID -> 300;
            case PIRATE -> 400;
            case TIGRESS -> 450;
            case SKULL_KING -> 500;
            case ESCAPE -> 0;   // 위에서 걸렸지만 switch 완전성
        };
    }

    /**
     * 손에 들고 있기 위험한 정도 — 나중에 원치 않는 트릭을 가져갈 위험. spendCost 와 같되
     * 티그리스만 −1: 어느 선언이든 고를 수 있어 들고 있어도 위험하지 않으므로 털 대상에서
     * 가장 뒤로 민다.
     */
    static int liability(PlayCard play) {
        return play.card().is(SpecialKind.TIGRESS) ? -1 : spendCost(play);
    }

    /**
     * 이길 수 없을 때 버리는 순서 — 일반·캐릭터 카드(spendCost 순) &lt; 탈출 &lt; 티그리스
     * (탈출 선언 &lt; 해적 선언). 탈출은 예측을 채운 뒤 확실히 지는 데, 티그리스는 어느
     * 쪽으로든 쓸 수 있어 가장 늦게 버린다. 그래도 [탈출, 탈출]·[티그리스]만 남으면 그중
     * 하나를 반드시 낸다.
     */
    static int discardKey(PlayCard play) {
        if (play.card().is(SpecialKind.TIGRESS)) {
            return 2000 + (play.declaredAs() == TigressMode.ESCAPE ? 0 : 1);
        }
        if (play.card().is(SpecialKind.ESCAPE)) {
            return 1000;
        }
        return spendCost(play);
    }
}
