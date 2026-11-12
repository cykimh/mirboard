package com.mirboard.domain.game.omok.action;

/**
 * 액션 거절 사유 (룰 §4). {@code name()} 이 그대로 STOMP {@code ERROR} 의 {@code code} 라 값 이름은 클라와의 계약이다.
 */
public enum RejectionReason {
    MATCH_OVER,
    NOT_YOUR_TURN,
    OUT_OF_BOARD,
    OCCUPIED,
    /** 흑 차례에 그 룰의 금수 자리 (룰 §6). 종류는 클라가 이미 받은 금수 목록에서 읽는다. */
    FORBIDDEN_MOVE,
    /** 강하게 두기(룰 §4.1, D-193)를 이 판에서 이미 썼다 — 착수 전체를 거절한다(상태 무변경). */
    SLAM_USED
}
