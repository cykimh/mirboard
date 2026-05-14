package com.mirboard.domain.game.tichu.scoring;

import com.mirboard.domain.game.tichu.card.Card;
import java.util.List;

/**
 * 라운드 점수에 관여하는 상수와 합산 헬퍼.
 *
 * <p>카드 점수 자체는 {@link Card#points()} 가 단일 진실 공급원. 본 클래스의 상수는
 * 라운드 종료 보너스(티츄/그랜드 티츄/더블 빅토리) 와 가독성용 카드 점수 상수다.
 */
public final class CardPoints {

    public static final int FIVE = 5;
    public static final int TEN = 10;
    public static final int KING = 10;
    public static final int DRAGON = 25;
    public static final int PHOENIX = -25;

    public static final int TICHU_BONUS = 100;
    public static final int GRAND_TICHU_BONUS = 200;
    public static final int DOUBLE_VICTORY_BONUS = 200;

    private CardPoints() {
    }

    public static int sum(List<Card> cards) {
        return cards.stream().mapToInt(Card::points).sum();
    }
}
