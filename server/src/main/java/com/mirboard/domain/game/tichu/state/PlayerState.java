package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.card.Card;
import java.util.List;

/**
 * 한 플레이어의 라운드 내 상태.
 *
 * @param seat            0..3 (0,2=Team A / 1,3=Team B)
 * @param hand            현재 보유 카드 (직렬화 시에는 본인 큐로만 전송)
 * @param declaration     티츄 / 그랜드 티츄 선언 상태
 * @param finishedOrder   카드를 모두 소진한 순서 (1=1등, 2=2등, ...) 또는 -1 (미소진)
 * @param tricksWon       이 라운드에서 본인이 가져간 트릭 카드 누적
 */
public record PlayerState(
        int seat,
        List<Card> hand,
        TichuDeclaration declaration,
        int finishedOrder,
        List<Card> tricksWon) {

    public PlayerState {
        hand = List.copyOf(hand);
        tricksWon = List.copyOf(tricksWon);
    }

    public static PlayerState initial(int seat, List<Card> hand) {
        return new PlayerState(seat, hand, TichuDeclaration.NONE, -1, List.of());
    }

    public boolean isFinished() {
        return finishedOrder > 0;
    }

    public int handSize() {
        return hand.size();
    }

    public Team team() {
        return Team.ofSeat(seat);
    }

    public PlayerState withHand(List<Card> newHand) {
        return new PlayerState(seat, newHand, declaration, finishedOrder, tricksWon);
    }

    public PlayerState withDeclaration(TichuDeclaration newDeclaration) {
        return new PlayerState(seat, hand, newDeclaration, finishedOrder, tricksWon);
    }

    public PlayerState withFinishedOrder(int order) {
        return new PlayerState(seat, hand, declaration, order, tricksWon);
    }
}
