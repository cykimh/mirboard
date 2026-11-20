package com.mirboard.domain.game.dambaduk.state;

/** 끝난 사유 (룰 §6). 이름은 클라와의 계약이다. */
public enum EndReason {
    /** ① 다툼 터가 하나도 없다. */
    SEPARATED,
    /** ② 다툼 터가 있지만 그 안의 어느 돌도 옮길 수 없다. */
    FROZEN,
    /** ③ 빠지지 않은 사람이 1명뿐(3인으로 시작한 판). */
    LAST_STANDING,
    /** ④ 2인 판의 시간패(같은 사람 연속 2번째 시간 초과). */
    TIMEOUT,
    /** ④ 2인 판의 탈주. */
    DESERTED
}
