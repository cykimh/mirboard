package com.mirboard.domain.game.dambaduk.state;

/** 터의 종류 (룰 §1). */
public enum RegionKind {
    /** 한 사람의 돌만 있다 — 그 사람의 집. */
    HOME,
    /** 두 사람 이상의 돌이 섞였다. */
    CONTESTED,
    /** 돌이 없다. */
    EMPTY
}
