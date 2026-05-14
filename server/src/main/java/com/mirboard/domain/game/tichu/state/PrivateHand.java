package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.card.Card;
import java.util.List;

/**
 * 본인 한 명만 받는 비공개 손패. {@code /user/queue/...} STOMP 큐로만 전송된다.
 */
public record PrivateHand(int seat, List<Card> cards) {
    public PrivateHand {
        cards = List.copyOf(cards);
    }
}
