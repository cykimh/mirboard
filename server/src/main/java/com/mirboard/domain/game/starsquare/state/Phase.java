package com.mirboard.domain.game.starsquare.state;

/** 단계(`docs/rules-starsquare.md` §6) — 포트 {@code phaseName} 이 이 이름을 그대로 쓴다. */
public enum Phase {
    /** 시작 3초 준비 — 퍼즐을 숨긴다(§6.1). */
    INTRO,
    /** 푸는 중(§6.2). */
    PLAYING,
    /** 퍼즐 사이 결과 보기 8초(§6.4). */
    RESULT,
    /** 매치 끝. */
    ENDED
}
