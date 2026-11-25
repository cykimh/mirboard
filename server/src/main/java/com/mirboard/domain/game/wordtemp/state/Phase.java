package com.mirboard.domain.game.wordtemp.state;

/**
 * 단계(`docs/rules-wordtemp.md` §3). 이름이 그대로 포트의 {@code phaseName} 이다. 대기실은 플랫폼 몫이라 단계가 아니다.
 */
public enum Phase {
    /** 모두 같이 이번 라운드의 정답 낱말을 고른다. */
    WORD_SELECT,
    /** 출제자가 힌트 0~5개. */
    HINT,
    /** 추리자마다 답 1개(또는 패스). */
    GUESS,
    /** 차수 결과 보기. */
    ATTEMPT_RESULT,
    /** 출제 차례 끝 — 정답과 그 차례의 힌트 전체가 공개돼 있다. */
    TURN_END,
    FINISHED
}
