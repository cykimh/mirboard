package com.mirboard.domain.game.tichu.hand;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mirboard.domain.game.tichu.card.Card;
import java.util.List;

/**
 * 검출된 족보. {@code cards} 는 항상 rank 오름차순으로 정렬되어 있고, {@code rank}
 * 는 같은 타입·길이 안에서 비교에 사용되는 대표값이다.
 *
 * <ul>
 *   <li>SINGLE → 카드 rank (Phoenix 단독은 phoenixSingle=true, rank 무관)</li>
 *   <li>PAIR/TRIPLE/BOMB → 모든 카드가 공유하는 rank</li>
 *   <li>FULL_HOUSE → 트리플의 rank</li>
 *   <li>STRAIGHT/CONSECUTIVE_PAIRS/STRAIGHT_FLUSH_BOMB → 가장 높은 rank</li>
 * </ul>
 *
 * <p>{@code phoenixSingle} 은 Phoenix 한 장만으로 SINGLE 을 만든 "플레이 시점" 의
 * 표식이다. 트릭에 안착된 이후에는 엔진이 자체 rank 로 정규화해서 본 플래그를 해제한다.
 */
public record Hand(HandType type, List<Card> cards, int rank, int length, boolean phoenixSingle) {

    public Hand {
        cards = List.copyOf(cards);
    }

    public Hand(HandType type, List<Card> cards, int rank, int length) {
        this(type, cards, rank, length, false);
    }

    @JsonIgnore
    public boolean isBomb() {
        return type.isBomb();
    }
}
