package com.mirboard.domain.game.wordtemp.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.wordtemp.state.Phase;

/**
 * 낱말 온도 액션 sealed 계층(`docs/rules-wordtemp.md` §12.1). {@code round}·{@code turnNo}·{@code attempt} 는 늦게 도착한 프레임을 거르는
 * 기준이다(지금과 다르면 {@code NOT_YOUR_PHASE}).
 *
 * <p>채점 결과 반영·봇 행동·단계 마감은 여기 없다 — 클라 JSON 으로 읽히는 계층에 두면 위조할 수 있으므로 엔진의 {@code applyScore}·
 * {@code onTimer} 가 맡는다. {@link Tick} 만 예외인데, 위조해도 <b>이미 지난 마감을 처리할 뿐</b>이다(§9).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WordTempAction.SelectWord.class, name = "SELECT_WORD"),
        @JsonSubTypes.Type(value = WordTempAction.SubmitHint.class, name = "SUBMIT_HINT"),
        @JsonSubTypes.Type(value = WordTempAction.EndHints.class, name = "END_HINTS"),
        @JsonSubTypes.Type(value = WordTempAction.SubmitGuess.class, name = "SUBMIT_GUESS"),
        @JsonSubTypes.Type(value = WordTempAction.Tick.class, name = "TICK")
})
public sealed interface WordTempAction extends GameAction
        permits WordTempAction.SelectWord,
                WordTempAction.SubmitHint,
                WordTempAction.EndHints,
                WordTempAction.SubmitGuess,
                WordTempAction.Tick {

    /** 이번 라운드의 정답 낱말 고르기. {@code index} = 내 후보 번호(0 부터). */
    record SelectWord(int round, int index) implements WordTempAction {
    }

    /** 힌트 내기(출제자). {@code text} 는 엔진이 정규화·검증한다. */
    record SubmitHint(int turnNo, int attempt, String text) implements WordTempAction {
    }

    /** [힌트 끝](출제자) — 1개 이상 냈고 채점 중이 아닐 때. */
    record EndHints(int turnNo, int attempt) implements WordTempAction {
    }

    /** 추리 답(추리자). 빈 문자열(공백·문장부호뿐) = 패스. */
    record SubmitGuess(int turnNo, int attempt, String text) implements WordTempAction {
    }

    /**
     * 서버 {@code timeoutAction} 용(§9) — "지금 단계가 그 단계이고 마감이 지났을 때만" 엔진 타이머의 마감 전이와 <b>같은 전이</b>를 하고,
     * 아니면 아무것도 바꾸지 않는다(멱등).
     */
    record Tick(int turnNo, int attempt, Phase phase) implements WordTempAction {
    }
}
