package com.mirboard.domain.game.wordtemp.state;

import com.mirboard.domain.game.wordtemp.WordTempConfig;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent.CandidateInfo;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent.HintInfo;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent.RevealedGuess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.AttemptResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Guess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.GuessEntry;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.MatchResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import java.util.ArrayList;
import java.util.List;

/**
 * 상태 → 뷰(`docs/rules-wordtemp.md` §12). 공개 뷰 타입에는 <b>후보·진행 중인 정답·미공개 힌트·공개 전 추리 필드 자체가 없다</b>(직렬화 타입 분리).
 * 무엇을 싣는지는 전부 {@link WordTempVisibility} 가 정한다.
 */
public final class WordTempViews {

    private WordTempViews() {
    }

    /**
     * 모두가 보는 판. 늦게 들어온 resync·관전 입장도 이것만 받는다.
     *
     * @param phaseRemainingMs 단계 마감까지 남은 시간(엔진 계산 — 화면은 바깥 {@code turnRemainingMs} 가 아니라 이것을 쓴다, §9)
     * @param hintCount        이번 차수 힌트 개수(확정 + 채점 중)
     * @param revealed         차수별 공개 힌트(쌓여 보인다)
     * @param guessedSeats     이번 차수에 추리를 낸 좌석(내용 없음)
     * @param attempts         이번 출제 차례의 끝난 차수 — 동시 공개된 답(금칙어는 가림)
     * @param history          끝난 출제 차례 요약(정답·공개 힌트·맞힌 좌석)
     * @param turnReveal       출제 차례 끝 동안만 — 방금 끝난 차례의 정답과 힌트 전체. 그 밖에는 null
     * @param result           끝났으면 결과
     */
    public record PublicView(Phase phase, int round, int rounds, int turnNo, int setterSeat, int attempt,
                             long phaseRemainingMs, int hintCount, int maxHints, List<Revealed> revealed,
                             List<Integer> guessedSeats, List<PublicAttempt> attempts, List<SeatView> seats,
                             List<Integer> order, List<TurnSummary> history, TurnReveal turnReveal,
                             MatchResult result) {
    }

    /** {@code chosen} = 이번 라운드 낱말을 골랐는가(무엇인지는 없다). */
    public record SeatView(int seat, boolean alive, boolean bot, int setterPoints, int guessPoints, int total,
                           boolean chosen) {
    }

    public record PublicAttempt(int attempt, List<RevealedGuess> guesses, int setterGain) {
    }

    public record TurnReveal(String answer, List<HintInfo> hints) {
    }

    /**
     * 본인만 보는 것.
     *
     * @param candidates 내 후보(낱말·범주·이번 라운드에 고를 수 있는가)
     * @param chosen     이번 라운드에 고른 후보 번호, 아직이면 −1
     * @param answer     (출제자면) 이번 정답, 아니면 null
     * @param hints      (출제자면) 이번 출제 차례의 힌트·온도·채점 중 표시, 아니면 빈 목록
     * @param myGuess    (추리자면) 이번 차수에 낸 내 답(빈 문자열 = 패스), 아직이면 null
     */
    public record PrivateView(int seat, List<CandidateInfo> candidates, int chosen, String answer,
                              List<OwnHint> hints, String myGuess) {
    }

    /** {@code temp} 는 확정이면 0~100, 아니면 −1. */
    public record OwnHint(String text, int attempt, HintStatus status, int temp) {
    }

    public static PublicView publicView(WordTempState state, WordTempConfig config, long now) {
        List<SeatView> seats = new ArrayList<>(state.seatCount());
        for (int s = 0; s < state.seatCount(); s++) {
            Seat seat = state.seat(s);
            seats.add(new SeatView(s, seat.alive(), config.bot(s), seat.setterPoints(), seat.guessPoints(),
                    seat.total(), seat.chosen() >= 0));
        }
        List<PublicAttempt> attempts = state.attempts().stream()
                .map(a -> new PublicAttempt(a.attempt(), revealedGuesses(a), a.setterGain())).toList();
        TurnReveal reveal = WordTempVisibility.turnSecretsPublic(state)
                ? new TurnReveal(state.answer().word(), hintInfos(state)) : null;
        long remaining = state.ended() ? 0 : Math.max(0, state.phaseStartedAt() + config.phaseMs(state.phase()) - now);
        return new PublicView(state.phase(), state.round(), config.rounds(), state.turnNo(), state.setterSeat(),
                state.attempt(), remaining, state.turnInProgress() ? state.hintCount() : 0,
                config.tuning().maxHints(), state.revealed(),
                state.guesses().stream().map(Guess::seat).sorted().toList(), attempts, seats, state.order(),
                state.history(), reveal, state.result());
    }

    /** {@code viewer} 의 비공개 뷰. 받을 수 없는 좌석(봇·빠진 좌석·관전자)이면 null. */
    public static PrivateView privateView(WordTempState state, WordTempConfig config, int viewer) {
        if (!WordTempVisibility.privateRecipients(state, config.bots()).contains(viewer)) {
            return null;
        }
        Seat seat = state.seat(viewer);
        List<CandidateInfo> candidates = WordTempVisibility.seesCandidates(state, viewer, viewer)
                ? candidateInfos(seat) : List.of();
        String answer = WordTempVisibility.seesAnswer(state, viewer) ? state.answer().word() : null;
        List<OwnHint> hints = WordTempVisibility.seesHints(state, viewer)
                ? state.hints().stream().map(h -> new OwnHint(h.text(), h.attempt(), h.status(), h.temp())).toList()
                : List.of();
        String myGuess = null;
        if (WordTempVisibility.seesOwnGuess(state, viewer)) {
            Guess g = state.guessOf(viewer);
            myGuess = g == null ? null : g.text();
        }
        return new PrivateView(viewer, candidates, seat.chosen(), answer, hints, myGuess);
    }

    public static List<CandidateInfo> candidateInfos(Seat seat) {
        List<CandidateInfo> infos = new ArrayList<>(seat.candidates().size());
        for (int i = 0; i < seat.candidates().size(); i++) {
            infos.add(new CandidateInfo(i, seat.candidates().get(i).word(), seat.candidates().get(i).category(),
                    seat.available(i)));
        }
        return infos;
    }

    /** 공개된 차수의 답들 — 가려진 답의 원문은 싣지 않는다. */
    public static List<RevealedGuess> revealedGuesses(AttemptResult attempt) {
        List<RevealedGuess> out = new ArrayList<>(attempt.guesses().size());
        for (GuessEntry g : attempt.guesses()) {
            String text = g.pass() ? null : g.masked() ? RevealedGuess.MASK : g.text();
            out.add(new RevealedGuess(g.seat(), text, g.pass(), g.correct(), g.masked()));
        }
        return out;
    }

    /** 그 차례의 확정 힌트 전체(무효·채점 중 제외). 출제 차례 끝에만 모두에게 나간다. */
    public static List<HintInfo> hintInfos(WordTempState state) {
        List<HintInfo> out = new ArrayList<>();
        for (Hint h : state.hints()) {
            if (h.status() == HintStatus.SCORED) {
                boolean revealed = state.revealed().stream()
                        .anyMatch(r -> r.attempt() == h.attempt() && r.text().equals(h.text()));
                out.add(new HintInfo(h.attempt(), h.text(), h.temp(), revealed));
            }
        }
        return out;
    }
}
