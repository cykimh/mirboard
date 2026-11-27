package com.mirboard.domain.game.wordtemp.action;

/**
 * 액션 거절 사유(`docs/rules-wordtemp.md` §5.2·§5.3). 이름이 그대로 STOMP {@code ERROR} 의 {@code code} 가 된다.
 * 힌트 판정 순서(D-315): {@code NOT_YOUR_PHASE} → {@code SCORING_IN_PROGRESS} → {@code HINT_LIMIT} → {@code HINT_FORMAT} → {@code HINT_BLOCKED} →
 * {@code HINT_IS_ANSWER} → {@code HINT_INSIDE_ANSWER} → {@code HINT_DUPLICATE} → (표 조회) {@code UNKNOWN_WORD}.
 */
public enum RejectionReason {
    MATCH_OVER,
    INVALID_ACTION,
    /** 그 단계가 아님·그 역할이 아님·빠진 좌석·봇 좌석·{@code round}/{@code turnNo}/{@code attempt} 불일치(늦게 도착)·마감이 지남. */
    NOT_YOUR_PHASE,
    /** 낱말 고르기 — 후보 번호가 범위 밖이거나 앞 라운드에 이미 쓴 후보(D-315). */
    BAD_CHOICE,
    /** 낱말 고르기 — 이번 라운드에 이미 골랐다(바꾸기 없음, D-315). */
    ALREADY_CHOSEN,
    HINT_FORMAT,
    /** 금칙어를 품은 힌트(D-320 덧붙임 — 힌트는 채점 중부터 공개). */
    HINT_BLOCKED,
    HINT_IS_ANSWER,
    HINT_INSIDE_ANSWER,
    HINT_DUPLICATE,
    HINT_LIMIT,
    SCORING_IN_PROGRESS,
    UNKNOWN_WORD,
    NO_HINTS_YET,
    GUESS_FORMAT,
    ALREADY_GUESSED,
    /** 온도 신고(D-323) — 이 출제 차례에 공개된(확정) 힌트가 아니다. */
    FLAG_UNKNOWN_HINT,
    /** 온도 신고 — 이 판에서 이미 신고한 힌트다. */
    FLAG_DUPLICATE,
    /** 온도 신고 — 이 판의 신고 횟수(좌석당 10번)를 다 썼다. */
    FLAG_LIMIT
}
