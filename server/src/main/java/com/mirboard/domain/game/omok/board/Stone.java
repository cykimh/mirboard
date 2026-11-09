package com.mirboard.domain.game.omok.board;

/** 돌 색 (룰 §1). */
public enum Stone {
    BLACK,
    WHITE;

    public Stone opposite() {
        return this == BLACK ? WHITE : BLACK;
    }
}
