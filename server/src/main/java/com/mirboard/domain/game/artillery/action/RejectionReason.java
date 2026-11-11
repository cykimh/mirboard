package com.mirboard.domain.game.artillery.action;

/** 거절 사유 (`docs/rules-artillery.md` §4). 비공개 {@code ERROR} 의 code 가 된다. */
public enum RejectionReason {
    NOT_YOUR_TURN,
    MATCH_OVER,
    OUT_OF_RANGE,
    NO_AMMO,
    /** 없는(이미 쓴) 아이템 (D-224). */
    NO_ITEM,
    INVALID_ACTION,
    /** 한 걸음도 못 감 — 연료 0 이거나 첫 걸음이 벽·전장 끝(D-228). 상태 무변경. */
    CANNOT_MOVE
}
