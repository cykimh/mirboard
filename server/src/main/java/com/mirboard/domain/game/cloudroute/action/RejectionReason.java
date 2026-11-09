package com.mirboard.domain.game.cloudroute.action;

/** 구름항로 액션 거절 사유. 이름이 그대로 STOMP {@code ERROR} 의 {@code code} 가 된다. */
public enum RejectionReason {
    /** 끝난 매치. */
    MATCH_OVER,
    /** 탈락한 좌석. */
    PLAYER_ELIMINATED,
    /** 차례가 아니다. */
    NOT_YOUR_TURN,
    /** 지금 기다리는 결정과 다른 행동. */
    WRONG_DECISION,
    /** 고를 수 없는 건설 단계. */
    INVALID_LEVEL,
    /** 후보에 없는 칸. */
    INVALID_TILE,
    /** 현금이 모자란다. */
    INSUFFICIENT_FUNDS,
    /** 구명 부표가 없다. */
    NO_BUOY
}
