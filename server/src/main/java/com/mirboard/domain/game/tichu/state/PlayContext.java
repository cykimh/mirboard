package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.card.Wish;
import com.mirboard.domain.game.tichu.hand.Hand;

/**
 * 한 번의 플레이를 검증·비교할 때 필요한 트릭 컨텍스트.
 * <ul>
 *   <li>{@code currentTop} — 현재 트릭의 가장 위 손패. null 이면 리드(첫 플레이).</li>
 *   <li>{@code activeWish} — Mahjong 소원이 활성되어 있다면 그 record. null 이면 없음.</li>
 * </ul>
 */
public record PlayContext(Hand currentTop, Wish activeWish) {

    public static PlayContext lead() {
        return new PlayContext(null, null);
    }

    public static PlayContext following(Hand currentTop) {
        return new PlayContext(currentTop, null);
    }

    public boolean isLead() {
        return currentTop == null;
    }

    public boolean hasActiveWish() {
        return activeWish != null && activeWish.isActive();
    }
}
