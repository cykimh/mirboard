package com.mirboard.domain.game.tichu.state;

public enum Team {
    A,
    B;

    public Team opponent() {
        return this == A ? B : A;
    }

    public static Team ofSeat(int seat) {
        return seat % 2 == 0 ? A : B;
    }
}
