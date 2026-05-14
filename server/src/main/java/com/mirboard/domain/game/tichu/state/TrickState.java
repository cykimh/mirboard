package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.card.Wish;
import com.mirboard.domain.game.tichu.hand.Hand;
import java.util.List;
import java.util.Set;

/**
 * 한 트릭의 진행 상태.
 *
 * @param leadSeat         이 트릭을 리드한 시트
 * @param currentTurnSeat  지금 차례인 시트
 * @param currentTop       현재 가장 위에 놓인 손패 (null=리드, 아직 아무도 안 냄)
 * @param currentTopSeat   currentTop 을 낸 시트 (currentTop 이 null 이면 -1)
 * @param passedSeats      이번 트릭에서 현재까지 패스한 시트 집합
 * @param accumulatedCards 이 트릭에 누적된 카드 (트릭 종료 시 가져가는 사람의 tricksWon 으로)
 * @param activeWish       Mahjong 으로 활성된 소원 (없으면 null)
 */
public record TrickState(
        int leadSeat,
        int currentTurnSeat,
        Hand currentTop,
        int currentTopSeat,
        Set<Integer> passedSeats,
        List<Hand> playSequence,
        List<com.mirboard.domain.game.tichu.card.Card> accumulatedCards,
        Wish activeWish) {

    public TrickState {
        passedSeats = Set.copyOf(passedSeats);
        playSequence = List.copyOf(playSequence);
        accumulatedCards = List.copyOf(accumulatedCards);
    }

    public static TrickState lead(int leadSeat, Wish activeWish) {
        return new TrickState(leadSeat, leadSeat, null, -1,
                Set.of(), List.of(), List.of(), activeWish);
    }

    public boolean isLead() {
        return currentTop == null;
    }

    public boolean hasActiveWish() {
        return activeWish != null && activeWish.isActive();
    }
}
