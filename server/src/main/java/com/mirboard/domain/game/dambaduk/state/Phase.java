package com.mirboard.domain.game.dambaduk.state;

/** 판 단계 (룰 §3·§4). */
public enum Phase {
    /** 뱀 순서로 돌을 놓는다. */
    PLACING,
    /** 옮기고 담 세우기. */
    PLAYING,
    /** 끝났다 — 결과가 있다. */
    ENDED
}
