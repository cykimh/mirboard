package com.mirboard.domain.game.starsquare.invariant;

import com.mirboard.domain.game.starsquare.StarSquareEngine;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.MagicCheck;
import com.mirboard.domain.game.starsquare.puzzle.Progress;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import com.mirboard.domain.game.starsquare.rules.MatchRanking;
import com.mirboard.domain.game.starsquare.state.BotPlan;
import com.mirboard.domain.game.starsquare.state.Phase;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.SeatBoard;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.List;

/**
 * 매 전이 뒤 성립해야 하는 것(`docs/rules-starsquare.md` §15). 위반을 모두 모아 한 번에 던진다.
 */
public final class StarSquareInvariantChecker {

    private StarSquareInvariantChecker() {
    }

    public static void check(StarSquareState state, StarSquareEngine engine) {
        List<String> violations = violations(state, engine);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Star square invariant violated: " + violations + " state=" + state);
        }
    }

    public static List<String> violations(StarSquareState state, StarSquareEngine engine) {
        List<String> v = new ArrayList<>();
        if (state.seatCount() != engine.config().seatCount()) {
            v.add("seat count " + state.seatCount() + " != config " + engine.config().seatCount());
        }
        if (state.puzzleCount() != engine.config().puzzleCount()) {
            v.add("puzzle count " + state.puzzleCount() + " != config " + engine.config().puzzleCount());
        }
        checkPuzzles(state, v);
        if (state.index() < 0 || state.index() >= state.puzzleCount()) {
            v.add("index out of range: " + state.index());
            return v;
        }
        for (int seat : state.deserted()) {
            if (seat < 0 || seat >= state.seatCount()) {
                v.add("deserted seat out of range: " + seat);
            }
        }
        checkBoards(state, engine, v);
        checkPhase(state, engine, v);
        checkOutcomes(state, v);
        return v;
    }

    private static void checkPuzzles(StarSquareState state, List<String> v) {
        for (int i = 0; i < state.puzzleCount(); i++) {
            PuzzleSpec p = state.puzzles().get(i);
            if (!MagicCheck.isMagic(p.solution(), p.clues())) {
                v.add("puzzle " + i + " solution is not a magic square matching its clues");
            }
            if (p.clues().nonZeroCount() != p.band().clueCount()) {
                v.add("puzzle " + i + " clue count " + p.clues().nonZeroCount());
            }
        }
    }

    private static void checkBoards(StarSquareState state, StarSquareEngine engine, List<String> v) {
        PuzzleSpec puzzle = state.puzzle();
        int nn = puzzle.n() * puzzle.n();
        List<Integer> places = new ArrayList<>();
        for (int seat = 0; seat < state.seatCount(); seat++) {
            SeatBoard b = state.board(seat);
            Grid g = b.grid();
            if (g.size() != nn) {
                v.add("seat " + seat + " grid size " + g.size());
                continue;
            }
            if (!puzzle.clues().nonZeroCellsMatch(g)) {
                v.add("seat " + seat + " board changed a clue");
            }
            Progress p = MagicCheck.progress(g);
            if (p.filled() != b.filled() || p.lines() != b.lines()) {
                v.add("seat " + seat + " stored progress " + b.progress() + " != " + p);
            }
            boolean magic = MagicCheck.isMagic(g, puzzle.clues());
            if (b.solved() != magic && state.phase() != Phase.INTRO) {
                v.add("seat " + seat + " solved=" + b.solved() + " but magic=" + magic);
            }
            if (b.solved()) {
                places.add(b.solvePlace());
                if (b.elapsedMs() < 0 || b.elapsedMs() > engine.config().limitMillis()) {
                    v.add("seat " + seat + " elapsed " + b.elapsedMs() + " outside the limit");
                }
            } else if (b.solvePlace() != 0 || b.elapsedMs() != -1) {
                v.add("seat " + seat + " unsolved but place/elapsed set");
            }
            boolean bot = engine.config().bot(seat);
            BotPlan pace = b.pace();
            if (!bot && pace != null) {
                v.add("human seat " + seat + " has a bot pace");
            }
            if (bot && b.version() != 0) {
                v.add("bot seat " + seat + " has a version");
            }
            if (pace != null) {
                if (pace.reached() < 0 || pace.reached() > pace.milestones()
                        || pace.milestones() < 1 || pace.milestones() > BotPlan.MAX_MILESTONES) {
                    v.add("seat " + seat + " bad pace " + pace);
                } else {
                    int expectedFilledBlanks = pace.cellsThrough(pace.reached());
                    int actualBlanks = g.nonZeroCount() - puzzle.clues().nonZeroCount();
                    if (expectedFilledBlanks != actualBlanks) {
                        v.add("bot seat " + seat + " filled " + actualBlanks + " != plan " + expectedFilledBlanks);
                    }
                    if (!g.nonZeroCellsMatch(puzzle.solution())) {
                        v.add("bot seat " + seat + " wrote a non-solution value");
                    }
                    if (b.solved() != pace.done()) {
                        v.add("bot seat " + seat + " solved=" + b.solved() + " but pace done=" + pace.done());
                    }
                }
            }
        }
        places.sort(Integer::compare);
        for (int i = 0; i < places.size(); i++) {
            if (places.get(i) != i + 1) {
                v.add("solve places not 1..k: " + places);
                break;
            }
        }
        long solved = state.boards().stream().filter(SeatBoard::solved).count();
        if ((solved > 0) != (state.firstSolvedAt() >= 0) && state.phase() == Phase.PLAYING) {
            v.add("firstSolvedAt " + state.firstSolvedAt() + " with " + solved + " solved");
        }
    }

    private static void checkPhase(StarSquareState state, StarSquareEngine engine, List<String> v) {
        List<Integer> pending = engine.pendingSeats(state);
        if (state.phase() == Phase.PLAYING) {
            List<Integer> expected = state.aliveSeats().stream()
                    .filter(s -> !engine.config().bot(s) && !state.board(s).solved()).toList();
            if (!pending.equals(expected)) {
                v.add("pending " + pending + " != " + expected);
            }
            if (engine.deadline(state) > state.phaseStartedAt() + engine.config().limitMillis()) {
                v.add("deadline after start + limit");
            }
            if (state.outcomes().size() != state.index()) {
                v.add("playing puzzle " + state.index() + " with " + state.outcomes().size() + " outcomes");
            }
        } else if (!pending.isEmpty()) {
            v.add("pending seats outside PLAYING: " + pending);
        }
        switch (state.phase()) {
            case INTRO -> {
                if (state.index() != 0 || !state.outcomes().isEmpty() || state.result() != null) {
                    v.add("intro must be before the first puzzle");
                }
            }
            case RESULT -> {
                if (state.outcomes().size() != state.index() + 1 || state.result() != null) {
                    v.add("result phase must follow puzzle " + state.index());
                }
                if (state.index() == state.puzzleCount() - 1) {
                    v.add("result phase after the last puzzle");
                }
            }
            case ENDED -> {
                if (state.result() == null) {
                    v.add("ended without result");
                } else {
                    var recomputed = MatchRanking.result(state, state.result().reason());
                    if (!recomputed.equals(state.result())) {
                        v.add("result " + state.result() + " != recomputed " + recomputed);
                    }
                    switch (state.result().reason()) {
                        case FINISHED -> {
                            if (state.outcomes().size() != state.puzzleCount()) {
                                v.add("FINISHED with " + state.outcomes().size() + " outcomes");
                            }
                        }
                        case NO_HUMANS -> {
                            if (state.aliveSeats().stream().anyMatch(s -> !engine.config().bot(s))) {
                                v.add("NO_HUMANS with a live human");
                            }
                        }
                        case LAST_SEAT -> {
                            if (state.aliveSeats().size() != 1 || state.seatCount() < 2) {
                                v.add("LAST_SEAT with alive " + state.aliveSeats());
                            }
                        }
                    }
                }
            }
            case PLAYING -> {
                if (state.result() != null) {
                    v.add("playing with a result");
                }
            }
        }
    }

    private static void checkOutcomes(StarSquareState state, List<String> v) {
        for (int i = 0; i < state.outcomes().size(); i++) {
            PuzzleOutcome o = state.outcomes().get(i);
            if (o.index() != i) {
                v.add("outcome " + i + " has index " + o.index());
            }
            if (o.results().size() != state.seatCount() || o.grids().size() != state.seatCount()) {
                v.add("outcome " + i + " size mismatch");
                continue;
            }
            PuzzleSpec p = state.puzzles().get(i);
            if (!o.solution().equals(p.solution())) {
                v.add("outcome " + i + " solution differs from the puzzle");
            }
            int alive = (int) o.results().stream().filter(r -> !r.deserted()).count();
            for (var r : o.results()) {
                int expectedPoints = r.deserted() ? 0 : state.seatCount() - r.rank();
                if (r.points() != expectedPoints || r.points() < 0) {
                    v.add("outcome " + i + " seat " + r.seat() + " points " + r.points());
                }
                if (r.deserted() && r.rank() != alive + 1) {
                    v.add("outcome " + i + " deserted rank " + r.rank());
                }
                if (r.solved() != MagicCheck.isMagic(o.grids().get(r.seat()), p.clues()) && !r.deserted()) {
                    v.add("outcome " + i + " seat " + r.seat() + " solved flag mismatch");
                }
            }
        }
        List<Integer> totals = state.totals();
        for (int t : totals) {
            if (t < 0) {
                v.add("negative total " + totals);
            }
        }
    }
}
