package com.mirboard.domain.game.omok.state;

/** 종료 사유 (룰 §10.1). 이름은 클라와의 계약이다. */
public enum EndReason {
    FIVE,
    RESIGN,
    TIMEOUT,
    DESERTED,
    BOARD_FULL,
    NO_LEGAL_MOVE;

    public boolean isDraw() {
        return this == BOARD_FULL || this == NO_LEGAL_MOVE;
    }
}
