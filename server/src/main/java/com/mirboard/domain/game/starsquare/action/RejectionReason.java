package com.mirboard.domain.game.starsquare.action;

/**
 * 액션 거절 사유(`docs/rules-starsquare.md` §7.1). 이름이 그대로 STOMP {@code ERROR} 의 {@code code} 가 된다. 판정 순서는 이 선언 순서가
 * 아니라 §7.1 의 "판정 순서" 다.
 */
public enum RejectionReason {
    MATCH_OVER,
    NOT_IN_PLAY,
    /** 조용히 — 푸는 단계가 아니거나 마감이 지남. */
    PUZZLE_CLOSED,
    /** 조용히 — 지난 퍼즐·낮은 버전·같은 버전에 다른 판. */
    STALE_SYNC,
    /** 조용히 — 이미 완성. */
    ALREADY_SOLVED,
    BAD_GRID,
    CLUE_CHANGED,
    INVALID_ACTION;

    /** 클라가 문구 없이 넘기는 사유(§7.1 *조용히*). */
    public boolean quiet() {
        return this == PUZZLE_CLOSED || this == STALE_SYNC || this == ALREADY_SOLVED;
    }
}
