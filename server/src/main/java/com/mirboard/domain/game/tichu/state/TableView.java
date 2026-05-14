package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.hand.Hand;
import java.util.List;
import java.util.Map;

/**
 * 모든 플레이어에게 공개되는 상태 스냅샷. 본인 손패 카드는 절대 포함하지 않고
 * 장수만 노출. {@link PrivateHand} 와 함께 직렬화 시점에서 엄격히 분리된다.
 */
public record TableView(
        int currentTurnSeat,
        Map<Integer, Integer> handCounts,
        Hand currentTop,
        int currentTopSeat,
        Map<Integer, TichuDeclaration> declarations,
        Map<Team, Integer> roundScores,
        List<Integer> finishingOrder,
        Integer activeWishRank) {

    public TableView {
        handCounts = Map.copyOf(handCounts);
        declarations = Map.copyOf(declarations);
        roundScores = Map.copyOf(roundScores);
        finishingOrder = List.copyOf(finishingOrder);
    }
}
