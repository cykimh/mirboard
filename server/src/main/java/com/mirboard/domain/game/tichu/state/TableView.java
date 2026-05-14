package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.hand.Hand;
import java.util.List;
import java.util.Map;

/**
 * 모든 플레이어에게 공개되는 상태 스냅샷. 본인 손패 카드는 절대 포함하지 않고
 * 장수만 노출. {@link PrivateHand} 와 함께 직렬화 시점에서 엄격히 분리된다.
 *
 * <p>Phase 5b 에서 단계별 정보 (phase, dealingCardCount, readySeats,
 * passingSubmittedSeats) 가 추가되었고, Phase 5c 에서 매치 누적 정보 (roundNumber,
 * matchScores) 가 추가되었다. 손패 카드는 여전히 비공개.
 */
public record TableView(
        String phase,
        int dealingCardCount,
        List<Integer> readySeats,
        List<Integer> passingSubmittedSeats,
        int currentTurnSeat,
        Map<Integer, Integer> handCounts,
        Hand currentTop,
        int currentTopSeat,
        Map<Integer, TichuDeclaration> declarations,
        Map<Team, Integer> roundScores,
        Map<Team, Integer> matchScores,
        int roundNumber,
        List<Integer> finishingOrder,
        Integer activeWishRank) {

    public TableView {
        readySeats = List.copyOf(readySeats);
        passingSubmittedSeats = List.copyOf(passingSubmittedSeats);
        handCounts = Map.copyOf(handCounts);
        declarations = Map.copyOf(declarations);
        roundScores = Map.copyOf(roundScores);
        matchScores = Map.copyOf(matchScores);
        finishingOrder = List.copyOf(finishingOrder);
    }
}
