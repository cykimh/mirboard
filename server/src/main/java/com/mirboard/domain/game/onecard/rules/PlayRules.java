package com.mirboard.domain.game.onecard.rules;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;

/** 지금 이 카드를 낼 수 있는가 (`docs/rules-onecard.md` §5.2, §5.3, §6.2). */
public final class PlayRules {

    private PlayRules() {
    }

    /**
     * @param top         버린 더미 맨 위
     * @param baseSuit    기준 무늬(§5.1) — 맨 위가 조커면 null
     * @param attackStack 공격 누적. 0 보다 크면 공격받는 중이다
     */
    public static boolean canPlay(PlayingCard top, Suit baseSuit, int attackStack, PlayingCard card) {
        return attackStack > 0 ? canCounter(top, baseSuit, card) : matches(top, baseSuit, card);
    }

    /** §5.2 — 공격받는 중이 아닐 때. 7 도 같은 조건이다(와일드 아님). */
    static boolean matches(PlayingCard top, Suit baseSuit, PlayingCard card) {
        if (card.isJoker() || top.isJoker()) {
            return true;
        }
        return card.suit() == baseSuit || card.rank() == top.rank();
    }

    /**
     * §6.2 — 공격받는 중일 때. 공격 카드만, 맨 위 공격 이상의 세기만. 같은 숫자는 무늬와 무관하고, 다른 숫자는
     * 기준 무늬가 같아야 하며, 조커는 무늬와 무관하다. 조커 공격에는 조커만 남는다(세기 조건).
     */
    static boolean canCounter(PlayingCard top, Suit baseSuit, PlayingCard card) {
        if (!card.isAttack() || card.attackStrength() < top.attackStrength()) {
            return false;
        }
        if (card.isJoker()) {
            return true;
        }
        return card.rank() == top.rank() || card.suit() == baseSuit;
    }
}
