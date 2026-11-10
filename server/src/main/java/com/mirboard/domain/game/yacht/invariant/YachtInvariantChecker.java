package com.mirboard.domain.game.yacht.invariant;

import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.rules.Ranking;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.TurnState;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 매 전이 뒤 성립해야 하는 것 (`docs/rules-yacht.md` §11). 위반을 모두 모아 한 번에 던진다.
 */
public final class YachtInvariantChecker {

    private YachtInvariantChecker() {
    }

    public static void check(YachtState state, Set<Integer> botSeats) {
        List<String> violations = violations(state, botSeats);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Yacht invariant violated: " + violations + " state=" + state);
        }
    }

    public static List<String> violations(YachtState state, Set<Integer> botSeats) {
        List<String> v = new ArrayList<>();
        checkCards(state, v);
        checkTurn(state, v);
        if (state.ended()) {
            checkEnded(state, botSeats, v);
        } else {
            checkOpen(state, botSeats, v);
        }
        return v;
    }

    private static void checkCards(YachtState state, List<String> v) {
        for (int seat = 0; seat < state.seatCount(); seat++) {
            Scorecard card = state.card(seat);
            for (var e : card.scores().entrySet()) {
                if (e.getValue() > maxPoints(e.getKey())) {
                    v.add("seat " + seat + " " + e.getKey() + "=" + e.getValue() + " above max");
                }
            }
        }
        for (int seat : state.deserted()) {
            if (seat < 0 || seat >= state.seatCount()) {
                v.add("deserted seat out of range: " + seat);
            }
        }
    }

    private static int maxPoints(Category c) {
        return switch (c) {
            case ONES, TWOS, THREES, FOURS, FIVES, SIXES -> c.face() * Dice.COUNT;
            case CHOICE, FOUR_OF_A_KIND, FULL_HOUSE -> Dice.FACES * Dice.COUNT;
            case SMALL_STRAIGHT -> 15;
            case LARGE_STRAIGHT -> 30;
            case YACHT -> 50;
        };
    }

    private static void checkTurn(YachtState state, List<String> v) {
        checkTurn(state.turn(), "turn", v);
        if (state.simultaneous()) {
            if (state.seatTurns().size() != state.seatCount()) {
                v.add("seat turns size " + state.seatTurns().size() + " != seats " + state.seatCount());
                return;
            }
            if (state.turn().rolls() != 0) {
                v.add("simultaneous round clock has dice");
            }
            for (int s = 0; s < state.seatCount(); s++) {
                checkTurn(state.seatTurns().get(s), "seat " + s, v);
                if (!state.pending(s) && state.seatTurns().get(s).rolls() != 0) {
                    v.add("seat " + s + " keeps dice while not pending");
                }
            }
        } else if (!state.seatTurns().isEmpty()) {
            v.add("sequential match with seat turns");
        }
    }

    private static void checkTurn(TurnState turn, String label, List<String> v) {
        if (turn.rolls() < 0 || turn.rolls() > TurnState.MAX_ROLLS) {
            v.add(label + " rolls out of range: " + turn.rolls());
        }
        if ((turn.dice() == null) != (turn.rolls() == 0)) {
            v.add(label + " dice present iff rolled: dice=" + turn.dice() + " rolls=" + turn.rolls());
        }
        if (turn.rolls() == 0 && !turn.held().none()) {
            v.add(label + " held before first roll");
        }
    }

    private static void checkOpen(YachtState state, Set<Integer> botSeats, List<String> v) {
        if (state.simultaneous()) {
            checkOpenSimultaneous(state, botSeats, v);
            return;
        }
        int seat = state.turnSeat();
        if (seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
            v.add("turn seat not alive: " + seat);
            return;
        }
        if (state.round() < 1 || state.round() > YachtState.ROUNDS) {
            v.add("round out of range: " + state.round());
        }
        for (int s : state.aliveSeats()) {
            int expected = s < seat ? state.round() : state.round() - 1;
            if (state.card(s).filledCount() != expected) {
                v.add("seat " + s + " filled " + state.card(s).filledCount() + " expected " + expected);
            }
        }
        if (state.aliveSeats().stream().allMatch(botSeats::contains)) {
            v.add("open match without a living human");
        }
        if (state.actionCount() >= state.actionLimit()) {
            v.add("open match at action limit");
        }
    }

    /** D-202 — 동시 진행: 살아 있는 좌석은 이번 라운드를 했거나(round) 아직(round − 1), 적어도 한 명은 아직이다. */
    private static void checkOpenSimultaneous(YachtState state, Set<Integer> botSeats, List<String> v) {
        if (state.turnSeat() != -1) {
            v.add("simultaneous match has turn seat " + state.turnSeat());
        }
        if (state.round() < 1 || state.round() > YachtState.ROUNDS) {
            v.add("round out of range: " + state.round());
        }
        for (int s : state.aliveSeats()) {
            int filled = state.card(s).filledCount();
            if (filled != state.round() && filled != state.round() - 1) {
                v.add("seat " + s + " filled " + filled + " in round " + state.round());
            }
        }
        if (state.pendingSeats().isEmpty()) {
            v.add("open simultaneous round with nobody pending");
        }
        if (state.aliveSeats().stream().allMatch(botSeats::contains)) {
            v.add("open match without a living human");
        }
        if (state.actionCount() >= state.actionLimit()) {
            v.add("open match at action limit");
        }
    }

    private static void checkEnded(YachtState state, Set<Integer> botSeats, List<String> v) {
        if (state.turnSeat() != -1) {
            v.add("ended match has turn seat " + state.turnSeat());
        }
        if (state.turn().dice() != null || state.seatTurns().stream().anyMatch(t -> t.dice() != null)) {
            v.add("ended match keeps dice");
        }
        EndReason reason = state.result().reason();
        switch (reason) {
            case FINISHED -> {
                if (state.aliveSeats().isEmpty()
                        || !state.aliveSeats().stream().allMatch(s -> state.card(s).isFull())) {
                    v.add("FINISHED without every living seat full");
                }
            }
            case NO_HUMANS -> {
                if (!state.aliveSeats().stream().allMatch(botSeats::contains)) {
                    v.add("NO_HUMANS with a living human");
                }
            }
            case ACTION_LIMIT -> {
                if (state.actionCount() < state.actionLimit()) {
                    v.add("ACTION_LIMIT below the limit");
                }
            }
        }
        if (!state.result().placements().equals(Ranking.placements(state))) {
            v.add("placements differ from ranking: " + state.result().placements());
        }
    }
}
