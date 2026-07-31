package com.mirboard.domain.game.skullking.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mirboard.domain.game.skullking.card.SkullCard;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 플레이어의 라운드 내 상태.
 *
 * <p>티츄 {@code PlayerState} 와 달리 팀·완주 순서가 없다. 스컬킹은 개인전이고 라운드가
 * "손패 소진"으로 한 번에 끝나므로 완주 순서 개념이 없다 (D-97 판단 1: 팀을 포트에서 뺀다).
 *
 * @param seat      좌석 (0 .. seatCount-1)
 * @param hand      현재 보유 카드 — 직렬화 시 본인 큐로만 나간다 (D-01)
 * @param bid       승수 예측. {@link #NO_BID} 면 미제출 (0 이 유효값이라 -1 을 센티널로 쓴다)
 * @param tricksWon 이 라운드에서 가져간 트릭들. 승수만이 아니라 카드까지 보관하는 이유는
 *                  포획 보너스 때문 (§9 함정, {@link TrickResult})
 */
public record PlayerState(int seat, List<SkullCard> hand, int bid, List<TrickResult> tricksWon) {

    /** 아직 입찰하지 않음. 0 이 유효한 예측값이라 0 을 센티널로 쓸 수 없다. */
    public static final int NO_BID = -1;

    public PlayerState {
        hand = List.copyOf(hand);
        tricksWon = List.copyOf(tricksWon);
    }

    public static PlayerState initial(int seat, List<SkullCard> hand) {
        return new PlayerState(seat, hand, NO_BID, List.of());
    }

    @JsonIgnore
    public boolean hasBid() {
        return bid != NO_BID;
    }

    @JsonIgnore
    public int handSize() {
        return hand.size();
    }

    /** 실제 승수 — §10 의 {@code won}. */
    @JsonIgnore
    public int tricksWonCount() {
        return tricksWon.size();
    }

    public PlayerState withBid(int newBid) {
        return new PlayerState(seat, hand, newBid, tricksWon);
    }

    public PlayerState withHand(List<SkullCard> newHand) {
        return new PlayerState(seat, newHand, bid, tricksWon);
    }

    /** 손패에서 카드 한 장을 뺀다 — 같은 값이 여러 장이면 하나만 (교환 가능하므로 무방). */
    public PlayerState withoutCard(SkullCard card) {
        List<SkullCard> next = new ArrayList<>(hand);
        if (!next.remove(card)) {
            throw new IllegalArgumentException("Seat " + seat + " does not hold " + card);
        }
        return withHand(next);
    }

    public PlayerState withTrickWon(TrickResult trick) {
        List<TrickResult> next = new ArrayList<>(tricksWon);
        next.add(trick);
        return new PlayerState(seat, hand, bid, next);
    }
}
