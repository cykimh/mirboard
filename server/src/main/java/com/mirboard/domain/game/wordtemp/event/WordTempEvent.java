package com.mirboard.domain.game.wordtemp.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.wordtemp.state.WordTempState.MatchResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.VoidReason;
import java.util.List;

/**
 * 낱말 온도 엔진이 발행하는 이벤트 sealed 계층(`docs/rules-wordtemp.md` §12.2·§12.3).
 *
 * <p><b>공개 이벤트는 전부 순번 있음.</b> 숨길 것(정답·후보·미공개 힌트와 온도·공개 전 추리)은 공개 이벤트 타입에 <b>필드 자체가 없다</b> —
 * {@link WordSelected}·{@link GuessSubmitted} 는 좌석만, {@link HintCount} 는 개수만 싣는다. 정답은 {@link TurnEnded} 에만 있다.
 *
 * <p><b>비공개 이벤트</b>({@link Private})는 받는 좌석의 본인 큐로만 가고 순번을 쓰지 않는다(D-126). 받는 좌석은
 * {@code WordTempVisibility} 가 정한 살아 있는 사람 좌석뿐이다(봇·빠진 좌석·관전자에게는 만들지 않는다).
 *
 * <p>payload 는 결과값이다(개수·총점). 시간은 남은 ms(절대 시각 아님 — 시계 차이).
 *
 * <p>{@code isGetterVisibility=NONE}: core {@code GameEvent.isPrivate()} 가 JSON 에 {@code "private"} 로 새지 않게.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WordTempEvent.WordSelectStarted.class, name = "WORD_SELECT_STARTED"),
        @JsonSubTypes.Type(value = WordTempEvent.WordSelected.class, name = "WORD_SELECTED"),
        @JsonSubTypes.Type(value = WordTempEvent.TurnStarted.class, name = "TURN_STARTED"),
        @JsonSubTypes.Type(value = WordTempEvent.HintPhaseStarted.class, name = "HINT_PHASE_STARTED"),
        @JsonSubTypes.Type(value = WordTempEvent.HintCount.class, name = "HINT_COUNT"),
        @JsonSubTypes.Type(value = WordTempEvent.HintRevealed.class, name = "HINT_REVEALED"),
        @JsonSubTypes.Type(value = WordTempEvent.GuessPhaseStarted.class, name = "GUESS_PHASE_STARTED"),
        @JsonSubTypes.Type(value = WordTempEvent.GuessSubmitted.class, name = "GUESS_SUBMITTED"),
        @JsonSubTypes.Type(value = WordTempEvent.GuessesRevealed.class, name = "GUESSES_REVEALED"),
        @JsonSubTypes.Type(value = WordTempEvent.TurnEnded.class, name = "TURN_ENDED"),
        @JsonSubTypes.Type(value = WordTempEvent.SeatOut.class, name = "SEAT_OUT"),
        @JsonSubTypes.Type(value = WordTempEvent.MatchEnded.class, name = "MATCH_ENDED"),
        @JsonSubTypes.Type(value = WordTempEvent.WordCandidates.class, name = "WORD_CANDIDATES"),
        @JsonSubTypes.Type(value = WordTempEvent.HintScored.class, name = "HINT_SCORED"),
        @JsonSubTypes.Type(value = WordTempEvent.HintPending.class, name = "HINT_PENDING"),
        @JsonSubTypes.Type(value = WordTempEvent.HintVoid.class, name = "HINT_VOID"),
        @JsonSubTypes.Type(value = WordTempEvent.GuessAccepted.class, name = "GUESS_ACCEPTED")
})
public sealed interface WordTempEvent extends GameEvent
        permits WordTempEvent.WordSelectStarted,
                WordTempEvent.WordSelected,
                WordTempEvent.TurnStarted,
                WordTempEvent.HintPhaseStarted,
                WordTempEvent.HintCount,
                WordTempEvent.HintRevealed,
                WordTempEvent.GuessPhaseStarted,
                WordTempEvent.GuessSubmitted,
                WordTempEvent.GuessesRevealed,
                WordTempEvent.TurnEnded,
                WordTempEvent.SeatOut,
                WordTempEvent.MatchEnded,
                WordTempEvent.Private {

    @Override
    default String envelopeType() {
        return switch (this) {
            case WordSelectStarted __ -> "WORD_SELECT_STARTED";
            case WordSelected __ -> "WORD_SELECTED";
            case TurnStarted __ -> "TURN_STARTED";
            case HintPhaseStarted __ -> "HINT_PHASE_STARTED";
            case HintCount __ -> "HINT_COUNT";
            case HintRevealed __ -> "HINT_REVEALED";
            case GuessPhaseStarted __ -> "GUESS_PHASE_STARTED";
            case GuessSubmitted __ -> "GUESS_SUBMITTED";
            case GuessesRevealed __ -> "GUESSES_REVEALED";
            case TurnEnded __ -> "TURN_ENDED";
            case SeatOut __ -> "SEAT_OUT";
            case MatchEnded __ -> "MATCH_ENDED";
            case WordCandidates __ -> "WORD_CANDIDATES";
            case HintScored __ -> "HINT_SCORED";
            case HintPending __ -> "HINT_PENDING";
            case HintVoid __ -> "HINT_VOID";
            case GuessAccepted __ -> "GUESS_ACCEPTED";
        };
    }

    // ---- 공개 (§12.2) ----

    /** 낱말 고르기 시작. 각자의 후보는 비공개 {@link WordCandidates} 로 따로 간다. */
    record WordSelectStarted(int round, long remainingMs) implements WordTempEvent {
    }

    /** 누가 골랐다(낱말 없음). */
    record WordSelected(int seat) implements WordTempEvent {
    }

    record TurnStarted(int turnNo, int round, int setterSeat) implements WordTempEvent {
    }

    record HintPhaseStarted(int turnNo, int attempt, long remainingMs) implements WordTempEvent {
    }

    /** 이번 차수의 힌트 개수(확정 + 채점 중)와 상한 — "힌트 3/5". */
    record HintCount(int count, int max) implements WordTempEvent {
    }

    /**
     * 차수의 공개 힌트. 힌트 0개로 끝났으면 {@code text} 가 null 이고 {@code temp} 는 −1.
     *
     * @param floor    이 차수 최저 온도
     * @param floorMet 공개 힌트가 최저 온도 이상인가 — 아니면 전원 오답이어도 출제자 0점(§3.4)
     */
    record HintRevealed(int attempt, String text, int temp, int floor, boolean floorMet) implements WordTempEvent {
    }

    /** @param seats 추리할 좌석들(살아 있고 출제자가 아닌 좌석) */
    record GuessPhaseStarted(int attempt, long remainingMs, List<Integer> seats) implements WordTempEvent {

        public GuessPhaseStarted {
            seats = List.copyOf(seats);
        }
    }

    /** 누가 냈다(내용 없음). */
    record GuessSubmitted(int seat) implements WordTempEvent {
    }

    /**
     * 추리 동시 공개 + 차수 점수.
     *
     * @param guesses    좌석별 답(오답도 낱말 그대로 — 금칙어에 걸리면 {@link RevealedGuess#MASK})
     * @param setterGain 이 차수에 출제자가 받은 점수
     * @param scores     좌석별 점수 증감·총점(모든 좌석)
     */
    record GuessesRevealed(int attempt, List<RevealedGuess> guesses, int setterSeat, int setterGain,
                           List<ScoreChange> scores, long remainingMs) implements WordTempEvent {

        public GuessesRevealed {
            guesses = List.copyOf(guesses);
            scores = List.copyOf(scores);
        }
    }

    /** @param text 답 낱말. 패스면 null, 가려졌으면 {@link #MASK}(원문은 이벤트에 없다) */
    record RevealedGuess(int seat, String text, boolean pass, boolean correct, boolean masked) {

        public static final String MASK = "···";
    }

    record ScoreChange(int seat, int delta, int total) {
    }

    /**
     * 출제 차례 끝 — <b>정답을 싣는 유일한 이벤트</b>.
     *
     * @param hints  그 차례의 확정 힌트 전체와 온도({@code revealed} = 차수의 공개 힌트였는가). 무효·채점 중이던 것은 없다
     * @param totals 좌석별 총점
     */
    record TurnEnded(int turnNo, String answer, List<HintInfo> hints, TurnEndReason reason, List<Integer> totals,
                     long remainingMs) implements WordTempEvent {

        public TurnEnded {
            hints = List.copyOf(hints);
            totals = List.copyOf(totals);
        }
    }

    record HintInfo(int attempt, String text, int temp, boolean revealed) {
    }

    /** 좌석이 빠졌다(탈주·강퇴·유예 만료 — §10). */
    record SeatOut(int seat) implements WordTempEvent {
    }

    /** 순위·총점(출제·추리 점수 나눠서)·끝난 이유. 안 쓴 후보는 없다. */
    record MatchEnded(MatchResult result) implements WordTempEvent {
    }

    // ---- 비공개 (§12.3) — 본인 큐, 순번 없음 ----

    sealed interface Private extends WordTempEvent
            permits WordCandidates, HintScored, HintPending, HintVoid, GuessAccepted {

        /** 받는 좌석. */
        int seat();

        @Override
        default int privateSeat() {
            return seat();
        }

        @Override
        default boolean sequenced() {
            return false;
        }
    }

    /** 내 후보와 이번 라운드에 고를 수 있는 것. */
    record WordCandidates(int seat, int round, List<CandidateInfo> candidates) implements Private {

        public WordCandidates {
            candidates = List.copyOf(candidates);
        }
    }

    /** {@code category} 는 화면에 그대로 쓰는 범주 이름(한글), {@code gloss} 는 뜻풀이 한 줄(없으면 빈 문자열). */
    record CandidateInfo(int index, String word, String category, String gloss, boolean available) {
    }

    /** 힌트의 온도가 확정됐다(표는 바로, 실시간은 반영될 때). {@code count} = 지금 개수. */
    record HintScored(int seat, String text, int temp, int count) implements Private {
    }

    /** 힌트가 채점 중이다 — 입력을 잠근다. 어댑터(S2)는 이것을 보고 채점을 요청한다. */
    record HintPending(int seat, String text) implements Private {
    }

    /** 채점 중이던 힌트가 무효가 됐다 — 개수를 돌려준다. */
    record HintVoid(int seat, String text, VoidReason reason, int count) implements Private {
    }

    /** 내가 낸 답(정규화된 값, 빈 문자열 = 패스). */
    record GuessAccepted(int seat, String text) implements Private {
    }
}
