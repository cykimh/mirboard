package com.mirboard.domain.game.onecard.bot;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.rules.TurnOrder;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.List;

/**
 * 봇이 보는 것 — 자기 손패와 공개 정보뿐이다 (D-128, 스컬킹 D-119 와 같은 원칙). 다른 좌석의 카드·뽑을
 * 더미 순서는 담지 않는다. {@link #of} 가 전체 상태를 읽는 유일한 지점이다.
 *
 * @param handCounts 좌석별 손패 장수(공개 정보). 탈락자는 0
 * @param nextSeat   방향대로 다음 살아 있는 좌석 — 내가 평범하게 내면 차례를 받을 사람
 */
public record OneCardBotView(int seat,
                             List<PlayingCard> hand,
                             PlayingCard topCard,
                             Suit baseSuit,
                             int attackStack,
                             List<Integer> handCounts,
                             int nextSeat) {

    public OneCardBotView {
        hand = List.copyOf(hand);
        handCounts = List.copyOf(handCounts);
    }

    public static OneCardBotView of(OneCardState state, int seat) {
        List<Integer> counts = new ArrayList<>();
        state.hands().forEach(h -> counts.add(h.size()));
        int next = TurnOrder.nextAlive(state.seatCount(), state::alive, seat, state.direction());
        return new OneCardBotView(seat, state.hands().get(seat), state.topCard(), state.baseSuit(),
                state.attackStack(), counts, next);
    }

    public boolean underAttack() {
        return attackStack > 0;
    }

    /** 다음 사람의 손패 장수. */
    public int nextHandCount() {
        return handCounts.get(nextSeat);
    }
}
