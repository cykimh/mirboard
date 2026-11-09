package com.mirboard.domain.game.yacht.action;

/** 액션 거절 사유 (`docs/rules-yacht.md` §4.1). 이름이 그대로 STOMP {@code ERROR} 의 {@code code} 가 된다. */
public enum RejectionReason {
    NOT_YOUR_TURN,
    MATCH_OVER,
    NO_ROLLS_LEFT,
    NOTHING_TO_ROLL,
    HOLD_NOT_ALLOWED,
    HOLD_UNCHANGED,
    NOT_ROLLED_YET,
    CATEGORY_FILLED,
    INVALID_ACTION
}
