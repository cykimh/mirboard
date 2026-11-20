package com.mirboard.domain.game.dambaduk.state;

/** 판에서 빠진 까닭 (룰 §8·§9). */
public enum ExitReason {
    /** 같은 사람 연속 2번째 시간 초과 — 탈주 수를 세지 않는다. */
    TIMEOUT,
    /** 탈주 확정. */
    DESERTED
}
