package com.mirboard.domain.game.artillery.action;

/** 거절 사유 (`docs/rules-artillery.md` §4). 비공개 {@code ERROR} 의 code 가 된다. */
public enum RejectionReason {
    NOT_YOUR_TURN,
    MATCH_OVER,
    OUT_OF_RANGE,
    NO_AMMO,
    INVALID_ACTION
}
