package com.mirboard.domain.game.dambaduk.action;

/**
 * 액션 거절 사유 (룰 §4.5). {@code name()} 이 그대로 STOMP {@code ERROR} 의 {@code code} 라 값 이름은 클라와의 계약이다. 선언 순서가
 * 판정 순서다 — 여러 사유에 걸리면 위쪽이 먼저(요청 모양 → 판과 맞춰 보기).
 */
public enum RejectionReason {
    MATCH_OVER,
    NOT_YOUR_TURN,
    WRONG_PHASE,
    OUT_OF_BOARD,
    NO_STONE_LEFT,
    OCCUPIED,
    NOT_YOUR_STONE,
    BAD_PATH,
    JUMP_UNAVAILABLE,
    PATH_BLOCKED,
    WALL_IN_PATH,
    JUMP_WITHOUT_WALL,
    WALL_SIDE_TAKEN
}
