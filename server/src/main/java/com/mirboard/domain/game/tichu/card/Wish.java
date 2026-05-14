package com.mirboard.domain.game.tichu.card;

/**
 * Mahjong 카드로 지정한 소원. 소원이 활성된 동안 다른 플레이어는 가능한 한 해당 rank 의
 * 카드가 들어가는 합법 플레이를 강제 받는다. 해당 rank 가 한 번이라도 트릭에서 나오면
 * {@link #fulfilled} 가 true 로 전이된다.
 */
public record Wish(int rank, boolean fulfilled) {

    public Wish {
        if (rank < 2 || rank > 14) {
            throw new IllegalArgumentException("Wish rank must be in [2, 14], got: " + rank);
        }
    }

    public static Wish active(int rank) {
        return new Wish(rank, false);
    }

    public Wish fulfill() {
        return fulfilled ? this : new Wish(rank, true);
    }

    public boolean isActive() {
        return !fulfilled;
    }
}
