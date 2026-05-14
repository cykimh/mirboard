package com.mirboard.domain.game.tichu.hand;

public enum HandType {
    SINGLE,
    PAIR,
    TRIPLE,
    FULL_HOUSE,
    STRAIGHT,
    CONSECUTIVE_PAIRS,
    BOMB,
    STRAIGHT_FLUSH_BOMB;

    public boolean isBomb() {
        return this == BOMB || this == STRAIGHT_FLUSH_BOMB;
    }
}
