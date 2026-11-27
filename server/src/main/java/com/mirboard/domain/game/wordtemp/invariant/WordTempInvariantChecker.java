package com.mirboard.domain.game.wordtemp.invariant;

import com.mirboard.domain.game.wordtemp.WordTempConfig;
import com.mirboard.domain.game.wordtemp.WordTempTuning;
import com.mirboard.domain.game.wordtemp.rules.CandidateDealer;
import com.mirboard.domain.game.wordtemp.rules.MatchRanking;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.AttemptResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.BotMove;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Guess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 매 전이 뒤 성립해야 하는 것(`docs/rules-wordtemp.md` §17). 위반을 모두 모아 한 번에 던진다. 공개 뷰·이벤트 JSON 에 비밀이 없다는 것은
 * 직렬화가 필요해 테스트 쪽({@code WordTempSecrecyTest}·시뮬레이션)이 본다.
 */
public final class WordTempInvariantChecker {

    private WordTempInvariantChecker() {
    }

    public static void check(WordTempState state, WordTempConfig config) {
        List<String> violations = violations(state, config);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Word temperature invariant violated: " + violations + " state=" + state);
        }
    }

    public static List<String> violations(WordTempState state, WordTempConfig config) {
        List<String> v = new ArrayList<>();
        WordTempTuning tuning = config.tuning();
        int n = state.seatCount();
        if (n != config.seatCount()) {
            v.add("seat count " + n + " != config " + config.seatCount());
            return v;
        }
        if (state.order().size() != n || new HashSet<>(state.order()).size() != n
                || state.order().stream().anyMatch(s -> s < 0 || s >= n)) {
            v.add("order is not a permutation of seats: " + state.order());
        }
        if (state.round() < 1 || state.round() > config.rounds()) {
            v.add("round out of range: " + state.round());
        }
        checkCandidates(state, config, v);
        checkScores(state, tuning, v);
        checkHistory(state, tuning, v);
        checkPhase(state, config, v);
        return v;
    }

    /** 후보 수 = max(3, R+1), 판 전체에서 겹치지 않음, 쓴 후보는 라운드마다 하나 이하·서로 다름. */
    private static void checkCandidates(WordTempState state, WordTempConfig config, List<String> v) {
        Set<String> all = new HashSet<>();
        for (int s = 0; s < state.seatCount(); s++) {
            Seat seat = state.seat(s);
            if (seat.candidates().size() != CandidateDealer.perSeat(config.rounds())) {
                v.add("seat " + s + " has " + seat.candidates().size() + " candidates");
            }
            for (var c : seat.candidates()) {
                if (!all.add(c.word())) {
                    v.add("candidate dealt twice: seat " + s);
                }
            }
            if (new HashSet<>(seat.used()).size() != seat.used().size() || seat.used().size() > state.round() - 1) {
                v.add("seat " + s + " used candidates " + seat.used() + " in round " + state.round());
            }
            if (seat.chosen() >= 0 && !seat.available(seat.chosen())) {
                v.add("seat " + s + " chose an unavailable candidate " + seat.chosen());
            }
            if (seat.setterPoints() < 0 || seat.guessPoints() < 0) {
                v.add("seat " + s + " has negative points");
            }
        }
    }

    /**
     * 점수 = 끝난 차례들의 합 + 아직 요약되지 않은 차례의 끝난 차수 합. 추리자가 빠져 판이 차례 도중에 끝나면 그 차례는 요약 없이 남는다
     * (받은 점수는 유지) — 그래서 "진행 중"이 아니라 "요약에 아직 없는가"로 가른다.
     */
    private static void checkScores(WordTempState state, WordTempTuning tuning, List<String> v) {
        int[] setter = new int[state.seatCount()];
        int[] guess = new int[state.seatCount()];
        for (TurnSummary t : state.history()) {
            setter[t.setterSeat()] += t.setterGain();
            for (int solver : t.solvers()) {
                guess[solver] += t.round();
            }
        }
        boolean summarized = !state.history().isEmpty()
                && state.history().get(state.history().size() - 1).turnNo() == state.turnNo();
        if (!summarized && state.inRange(state.setterSeat())) {
            for (AttemptResult a : state.attempts()) {
                setter[state.setterSeat()] += a.setterGain();
                for (int solver : a.solvers()) {
                    guess[solver] += state.round();
                }
            }
        }
        for (int s = 0; s < state.seatCount(); s++) {
            if (state.seat(s).setterPoints() != setter[s] || state.seat(s).guessPoints() != guess[s]) {
                v.add("seat " + s + " points " + state.seat(s).setterPoints() + "/" + state.seat(s).guessPoints()
                        + " != history " + setter[s] + "/" + guess[s]);
            }
        }
    }

    /** 한 차례 출제자 ≤ 차수 합 × r(5차면 15r), 추리자는 차례당 한 번만 맞힌다, 차수 ≤ 상한. */
    private static void checkHistory(WordTempState state, WordTempTuning tuning, List<String> v) {
        int maxGain = tuning.maxAttempts() * (tuning.maxAttempts() + 1) / 2;
        int lastTurn = 0;
        for (TurnSummary t : state.history()) {
            if (t.turnNo() <= lastTurn) {
                v.add("history turn numbers not increasing at " + t.turnNo());
            }
            lastTurn = t.turnNo();
            if (t.setterGain() < 0 || t.setterGain() > maxGain * t.round()) {
                v.add("turn " + t.turnNo() + " setter gain " + t.setterGain() + " > " + maxGain + "r");
            }
            if (!t.solvers().isEmpty() && t.setterGain() > (maxGain - tuning.maxAttempts()) * t.round()) {
                v.add("turn " + t.turnNo() + " was solved but the setter got all attempts");
            }
            if (new HashSet<>(t.solvers()).size() != t.solvers().size() || t.solvers().contains(t.setterSeat())) {
                v.add("turn " + t.turnNo() + " solvers " + t.solvers());
            }
            if (t.revealed().size() > tuning.maxAttempts()) {
                v.add("turn " + t.turnNo() + " revealed " + t.revealed().size() + " hints");
            }
        }
        if (lastTurn > state.turnNo()) {
            v.add("history is ahead of turnNo");
        }
    }

    private static void checkPhase(WordTempState state, WordTempConfig config, List<String> v) {
        WordTempTuning tuning = config.tuning();
        Phase phase = state.phase();
        if ((state.result() != null) != state.ended()) {
            v.add("result/phase mismatch: " + phase);
            return;
        }
        if (state.ended()) {
            if (!state.botPlan().isEmpty() || state.pendingHint() != null) {
                v.add("ended match still has bot moves or a pending hint");
            }
            if (!MatchRanking.result(state.seats(), state.result().endReason()).equals(state.result())) {
                v.add("result does not match the seats");
            }
            return;
        }
        List<Integer> alive = state.aliveSeats();
        if (alive.size() < 2 || alive.stream().allMatch(config::bot)) {
            v.add("match goes on with alive seats " + alive);
        }
        for (BotMove move : state.botPlan()) {
            if (!config.bot(move.seat()) || !state.alive(move.seat())) {
                v.add("bot move for a non-bot or out seat " + move.seat());
            }
        }
        if (phase == Phase.WORD_SELECT) {
            if (state.setterSeat() != -1 || state.attempt() != 0 || state.answer() != null
                    || !state.hints().isEmpty() || !state.guesses().isEmpty() || !state.botPlan().isEmpty()) {
                v.add("word select carries turn data");
            }
            return;
        }
        // 출제 차례 안(HINT·GUESS·ATTEMPT_RESULT·TURN_END)
        if (state.turnNo() < 1 || !state.inRange(state.setterSeat()) || state.answer() == null) {
            v.add("turn without setter/answer");
            return;
        }
        Seat setter = state.seat(state.setterSeat());
        if (setter.chosen() < 0 || !setter.candidates().get(setter.chosen()).equals(state.answer())) {
            v.add("answer is not the setter's chosen candidate");
        }
        if (state.turnInProgress() && !setter.alive()) {
            v.add("turn in progress with an out setter");
        }
        if (state.attempt() < 1 || state.attempt() > tuning.maxAttempts()) {
            v.add("attempt out of range: " + state.attempt());
        }
        int pending = 0;
        Set<String> live = new HashSet<>();
        for (int a = 1; a <= tuning.maxAttempts(); a++) {
            int attempt = a;
            long count = state.hints().stream()
                    .filter(h -> h.attempt() == attempt && h.status() != HintStatus.VOID).count();
            if (count > tuning.maxHints()) {
                v.add("attempt " + a + " has " + count + " hints");
            }
        }
        for (Hint h : state.hints()) {
            if (h.attempt() < 1 || h.attempt() > state.attempt()) {
                v.add("hint in a future attempt: " + h.attempt());
            }
            if (h.status() == HintStatus.PENDING) {
                pending++;
                if (h.attempt() != state.attempt()) {
                    v.add("pending hint from an earlier attempt");
                }
            }
            if (h.status() == HintStatus.SCORED && (h.temp() < 0 || h.temp() > 100)) {
                v.add("scored hint temp " + h.temp());
            }
            if (h.status() != HintStatus.VOID && !live.add(h.text())) {
                v.add("duplicate hint in a turn");
            }
        }
        if (pending > 1 || (pending == 1 && phase != Phase.HINT)) {
            v.add(pending + " pending hints in " + phase);
        }
        if (state.liveRequests() > config.liveCap()) {
            v.add("live requests " + state.liveRequests() + " > cap");
        }
        int expectedRevealed = switch (phase) {
            case HINT -> state.attempt() - 1;
            case GUESS, ATTEMPT_RESULT -> state.attempt();
            default -> -1;
        };
        if (expectedRevealed >= 0 && state.revealed().size() != expectedRevealed) {
            v.add("revealed " + state.revealed().size() + " hints in " + phase + " attempt " + state.attempt());
        }
        for (Revealed r : state.revealed()) {
            // D-320 — 차수 요약: 그 차수의 가장 높은 확정 힌트(없으면 낱말 null·온도 −1)와 기준을 넘겼는지
            int top = state.hints().stream().filter(h -> h.attempt() == r.attempt()
                    && h.status() == HintStatus.SCORED).mapToInt(Hint::temp).max().orElse(-1);
            boolean none = top < 0;
            if (r.temp() != top || (r.text() == null) != none || r.floor() != tuning.floor(r.attempt())
                    || r.floorMet() != (top >= r.floor())) {
                v.add("attempt summary of attempt " + r.attempt() + " does not match its hints");
            }
        }
        for (int a = 1; a <= tuning.maxAttempts(); a++) {
            int attempt = a;
            long passing = state.hints().stream().filter(h -> h.attempt() == attempt
                    && h.status() == HintStatus.SCORED && h.temp() >= tuning.floor(attempt)).count();
            if (passing > 1) {
                v.add("attempt " + a + " has " + passing + " hints at or above the floor");
            }
        }
        int expectedAttempts = switch (phase) {
            case HINT, GUESS -> state.attempt() - 1;
            case ATTEMPT_RESULT -> state.attempt();
            default -> -1;
        };
        if (expectedAttempts >= 0 && state.attempts().size() != expectedAttempts) {
            v.add("attempt results " + state.attempts().size() + " in " + phase + " attempt " + state.attempt());
        }
        for (AttemptResult a : state.attempts()) {
            boolean solved = !a.solvers().isEmpty();
            // D-320 — 전원 오답이고 그 차수가 기준 온도를 넘겼을 때만 출제자가 차수 × 라운드를 받는다. 그 밖에는 0.
            boolean floorMet = state.revealed().stream().anyMatch(r -> r.attempt() == a.attempt() && r.floorMet());
            int expectedGain = !solved && floorMet ? a.attempt() * state.round() : 0;
            if (a.setterGain() != expectedGain) {
                v.add("attempt " + a.attempt() + " setter gain " + a.setterGain() + " != " + expectedGain);
            }
            if (solved && phase != Phase.TURN_END && a.attempt() != state.attempt()) {
                v.add("turn went on after attempt " + a.attempt() + " was solved");
            }
        }
        if (phase != Phase.GUESS && !state.guesses().isEmpty()) {
            v.add("unrevealed guesses outside the guess phase");
        }
        Set<Integer> guessed = new HashSet<>();
        for (Guess g : state.guesses()) {
            if (g.seat() == state.setterSeat() || !state.alive(g.seat()) || !guessed.add(g.seat())) {
                v.add("bad guess from seat " + g.seat());
            }
        }
        if (phase != Phase.HINT && phase != Phase.GUESS && !state.botPlan().isEmpty()) {
            v.add("bot moves planned in " + phase);
        }
    }
}
