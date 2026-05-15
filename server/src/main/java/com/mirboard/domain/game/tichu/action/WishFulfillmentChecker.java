package com.mirboard.domain.game.tichu.action;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandComparator;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import java.util.List;

/**
 * Phase 10C — Mahjong wish 활성 상태에서 follow 차례에 "wish rank 카드를 포함한
 * 합법 follow 가 존재하는가?" 판단.
 *
 * <p>존재하면 ActionValidator 는 wish rank 미포함 플레이를 reject 한다 (보유한 wish
 * rank 를 무시할 수 없음). 존재하지 않으면 (current top 이 너무 강해서 wish rank
 * 로 못 이김) 자유 플레이 허용.
 *
 * <p>**1차 구현 범위** (D-58):
 * <ul>
 *   <li>wish rank 단일 카드 (SINGLE)</li>
 *   <li>wish rank 페어 (같은 rank 2장)</li>
 *   <li>wish rank 트리플 (같은 rank 3장)</li>
 *   <li>wish rank + Phoenix 페어 (Phoenix 와일드)</li>
 *   <li>wish rank + Phoenix + 같은 rank 1장 = 트리플 (Phoenix 와일드)</li>
 * </ul>
 *
 * <p>**1차 미지원** (사용자 요청 시 D-NN 신설하고 확장): wish rank 가 들어간 STRAIGHT,
 * FULL_HOUSE, CONSECUTIVE_PAIRS, BOMB. 표준 Tichu 룰상 wish 강제는 모든 합법 핸드
 * 포함해야 하나, 봇 시뮬레이션 + 친구 시연 규모에서는 단일/페어/트리플로 충분.
 * 콤보 강제까지 가려면 LegalActionEnumerator 의 풀 enumeration 재사용 권장.
 */
public final class WishFulfillmentChecker {

    private WishFulfillmentChecker() {
    }

    /**
     * @param hand       플레이어 손패
     * @param currentTop 현재 트릭 탑 (follow 컨텍스트이므로 null 아님)
     * @param wishRank   활성 wish rank (2~14)
     * @return true 이면 wish rank 카드를 포함한 합법 follow 가 존재 → reject 해야 함
     */
    public static boolean canPlayWishRank(List<Card> hand, Hand currentTop, int wishRank) {
        if (currentTop == null) return false;

        List<Card> wishCards = hand.stream()
                .filter(c -> c.isNormal() && c.rank() == wishRank)
                .toList();
        if (wishCards.isEmpty()) return false;

        boolean hasPhoenix = hand.stream().anyMatch(c -> c.is(Special.PHOENIX));

        // 1) wish rank 단일 카드
        for (Card c : wishCards) {
            if (canBeatAs(List.of(c), currentTop)) return true;
        }

        // 2) wish rank 페어 (2장 이상 보유)
        if (wishCards.size() >= 2) {
            if (canBeatAs(wishCards.subList(0, 2), currentTop)) return true;
        }

        // 3) wish rank 트리플 (3장 이상 보유)
        if (wishCards.size() >= 3) {
            if (canBeatAs(wishCards.subList(0, 3), currentTop)) return true;
        }

        // 4) wish rank + Phoenix 페어 (Phoenix 와일드로 페어 완성)
        if (hasPhoenix) {
            for (Card c : wishCards) {
                if (canBeatAs(List.of(c, Card.phoenix()), currentTop)) return true;
            }

            // 5) wish rank 2장 + Phoenix = 트리플
            if (wishCards.size() >= 2) {
                List<Card> tripleWithPhoenix = List.of(
                        wishCards.get(0), wishCards.get(1), Card.phoenix());
                if (canBeatAs(tripleWithPhoenix, currentTop)) return true;
            }
        }

        return false;
    }

    private static boolean canBeatAs(List<Card> cards, Hand currentTop) {
        return HandDetector.detect(cards)
                .map(h -> HandComparator.canBeat(h, currentTop))
                .orElse(false);
    }
}
