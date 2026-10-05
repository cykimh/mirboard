package com.mirboard.domain.game.onecard.bot;

import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 원카드 휴리스틱 봇 (D-128, 설계서 §4.6). <b>결정적</b>이다 — 같은 뷰·합법수면 같은 수를 낸다.
 *
 * <ul>
 *   <li><b>공격받는 중</b>: 가장 약한 반격 카드를 낸다(센 카드는 다음 반격용으로 아낀다). 없으면 먹는다.</li>
 *   <li><b>다음 사람이 {@value #THREAT_HAND_SIZE}장 이하</b>: 공격 카드(약한 것부터) → J → Q 로 압박·지연한다.</li>
 *   <li><b>평소</b>: 숫자 카드(가장 많이 든 무늬부터, 같으면 높은 숫자) → K → J → Q → 7 → 2·A → 조커. 공격
 *       카드는 반격 수단이라 마지막에 쓴다.</li>
 *   <li><b>7</b> 은 7 을 뺀 손패에서 가장 많이 든 무늬로 지정한다.</li>
 *   <li>낼 수 있으면 먹지 않는다.</li>
 * </ul>
 * 외치기 경쟁은 여기서 다루지 않는다 — 봇의 누름은 경쟁 창을 열 때 추첨한 반응 시간으로 엔진 타이머가
 * 맡는다(룰 §9-6).
 */
public final class OneCardBotPolicy {

    /** 다음 사람의 손패가 이 장수 이하이면 압박한다. */
    static final int THREAT_HAND_SIZE = 2;

    private OneCardBotPolicy() {
    }

    public static OneCardAction choose(OneCardBotView view, List<OneCardAction> legal) {
        List<PlayCard> plays = legal.stream()
                .filter(PlayCard.class::isInstance)
                .map(PlayCard.class::cast)
                .toList();
        if (plays.isEmpty()) {
            return draw(legal);
        }
        Comparator<PlayCard> order;
        if (view.underAttack()) {
            order = Comparator.comparingInt(play -> play.card().attackStrength());
        } else if (threatened(view)) {
            order = Comparator.<PlayCard>comparingInt(play -> pressureRank(play.card()))
                    .thenComparing(calmOrder(view));
        } else {
            order = calmOrder(view);
        }
        // min 은 동률이면 앞의 것을 고른다 — 합법수가 손패 순서라 결정적이다.
        return plays.stream().min(order).orElseThrow();
    }

    private static boolean threatened(OneCardBotView view) {
        return view.nextSeat() != view.seat() && view.nextHandCount() <= THREAT_HAND_SIZE;
    }

    /** 압박 순서 — 공격(약한 것부터) → J → Q, 나머지는 평소 순서로 넘긴다. */
    private static int pressureRank(PlayingCard card) {
        if (card.isAttack()) {
            return card.attackStrength();
        }
        if (card.isSkip()) {
            return 10;
        }
        if (card.isReverse()) {
            return 11;
        }
        return 20;
    }

    private static Comparator<PlayCard> calmOrder(OneCardBotView view) {
        Map<Suit, Integer> suitCounts = suitCounts(view.hand());
        return Comparator.<PlayCard>comparingInt(play -> calmRank(play.card()))
                .thenComparingInt(play -> -suitCount(suitCounts, play.card()))
                .thenComparingInt(play -> -play.card().rank())
                .thenComparingInt(play -> play.card().attackStrength())
                .thenComparingInt(play -> declarationRank(view.hand(), play));
    }

    /** 숫자 → K → J → Q → 7 → 2·A → 조커. */
    private static int calmRank(PlayingCard card) {
        if (card.isNormal()) {
            return 0;
        }
        if (card.isExtraTurn()) {
            return 1;
        }
        if (card.isSkip()) {
            return 2;
        }
        if (card.isReverse()) {
            return 3;
        }
        if (card.isSuitChange()) {
            return 4;
        }
        return card.isJoker() ? 6 : 5;
    }

    /** 7 의 지정 무늬 — 7 을 뺀 손패에서 많이 든 무늬일수록 앞. 7 이 아니면 0. */
    private static int declarationRank(List<PlayingCard> hand, PlayCard play) {
        if (play.declaredSuit() == null) {
            return 0;
        }
        int count = 0;
        for (PlayingCard card : hand) {
            if (!card.equals(play.card()) && !card.isJoker() && card.suit() == play.declaredSuit()) {
                count++;
            }
        }
        return -count;
    }

    private static int suitCount(Map<Suit, Integer> counts, PlayingCard card) {
        return card.isJoker() ? 0 : counts.getOrDefault(card.suit(), 0);
    }

    private static Map<Suit, Integer> suitCounts(List<PlayingCard> hand) {
        Map<Suit, Integer> counts = new EnumMap<>(Suit.class);
        for (PlayingCard card : hand) {
            if (!card.isJoker()) {
                counts.merge(card.suit(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static OneCardAction draw(List<OneCardAction> legal) {
        for (OneCardAction action : legal) {
            if (action instanceof OneCardAction.Draw) {
                return action;
            }
        }
        return legal.getFirst();
    }
}
