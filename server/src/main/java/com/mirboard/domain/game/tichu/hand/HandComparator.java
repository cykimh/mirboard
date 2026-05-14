package com.mirboard.domain.game.tichu.hand;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;

/**
 * 두 족보의 우열 판정. 사용 측 단일 진입점은 {@link #canBeat(Hand, Hand)} —
 * "도전자가 현재 트릭의 마지막 플레이를 끊을 수 있는가?".
 *
 * 규칙:
 * <ul>
 *   <li>STRAIGHT_FLUSH_BOMB 은 어떤 것도 끊을 수 있고, 더 긴 SFB → 더 높은 rank 순.</li>
 *   <li>일반 BOMB 은 비-BOMB 을 항상 끊고, BOMB 끼리는 rank 비교.</li>
 *   <li>그 외에는 같은 타입·같은 길이일 때만 rank 로 비교.</li>
 *   <li>Phoenix 단독 SINGLE 은 현재 SINGLE 의 rank 위에 0.5 — Dragon 만 못 끊는다.</li>
 * </ul>
 */
public final class HandComparator {

    private HandComparator() {
    }

    public static boolean canBeat(Hand challenger, Hand current) {
        if (challenger == null || current == null) return false;

        // Phoenix single: beats any SINGLE except Dragon. Bomb/SFB still loses to bombs.
        if (challenger.phoenixSingle()) {
            if (current.type() != HandType.SINGLE) return false;
            if (current.isBomb()) return false;
            Card top = current.cards().get(0);
            return !top.is(Special.DRAGON);
        }

        HandType c = challenger.type();
        HandType o = current.type();

        if (c == HandType.STRAIGHT_FLUSH_BOMB) {
            if (o == HandType.STRAIGHT_FLUSH_BOMB) {
                if (challenger.length() != current.length()) {
                    return challenger.length() > current.length();
                }
                return challenger.rank() > current.rank();
            }
            return true;
        }
        if (o == HandType.STRAIGHT_FLUSH_BOMB) {
            return false;
        }

        if (c == HandType.BOMB) {
            if (o == HandType.BOMB) {
                return challenger.rank() > current.rank();
            }
            return true;
        }
        if (o == HandType.BOMB) {
            return false;
        }

        if (c != o) return false;
        if (challenger.length() != current.length()) return false;
        return challenger.rank() > current.rank();
    }
}
