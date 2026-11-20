package com.mirboard.domain.game.starsquare.invariant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.starsquare.StarSquareConfig;
import com.mirboard.domain.game.starsquare.StarSquareEngine;
import com.mirboard.domain.game.starsquare.action.StarSquareAction;
import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.puzzle.Band;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.Phase;
import com.mirboard.domain.game.starsquare.state.SeatBoard;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** 불변식 검사기가 고의 위반을 하나씩 잡는다(위반마다 그 규칙이 걸린다). */
class StarSquareInvariantCheckerTest {

    static final Grid LO_SHU = Grid.of(2, 7, 6, 9, 5, 1, 4, 3, 8);
    static final Grid CLUES = Grid.of(2, 0, 0, 0, 0, 1, 0, 0, 0);

    private final StarSquareEngine engine = new StarSquareEngine(
            new StarSquareConfig(3, Set.of(2), 2, Difficulty.EASY, BotLevel.NORMAL, 300));

    private StarSquareState valid() {
        var puzzles = List.of(new PuzzleSpec(Band.THREE_EASY, CLUES, LO_SHU), new PuzzleSpec(Band.THREE_EASY, CLUES, LO_SHU));
        StarSquareState s = engine.onTimer(engine.start(puzzles, 5L, 0).newState(), 3_000).newState();
        s = engine.apply(s, 0, new StarSquareAction.Sync(0, 1, LO_SHU.toList()), 4_000).newState();
        return engine.apply(s, 1, new StarSquareAction.Sync(0, 1, List.of(2, 7, 6, 0, 0, 1, 0, 0, 0)), 4_500)
                .newState();
    }

    private StarSquareState boards(StarSquareState s, int seat, UnaryOperator<SeatBoard> change) {
        List<SeatBoard> next = new ArrayList<>(s.boards());
        next.set(seat, change.apply(s.board(seat)));
        return new StarSquareState(s.puzzles(), s.index(), s.phase(), s.phaseStartedAt(), s.firstSolvedAt(), next,
                s.deserted(), s.outcomes(), s.matchSeed(), s.result());
    }

    @Test
    void a_valid_state_passes() {
        assertThat(StarSquareInvariantChecker.violations(valid(), engine)).isEmpty();
    }

    @Test
    void catches_each_deliberate_violation() {
        StarSquareState s = valid();
        expect(boards(s, 1, b -> new SeatBoard(b.grid(), b.version(), b.filled() + 1, b.lines(), 0, -1, null)),
                "stored progress");
        expect(boards(s, 1, b -> new SeatBoard(Grid.of(4, 7, 6, 0, 0, 1, 0, 0, 0), b.version(), 4, 1, 0, -1, null)),
                "changed a clue");
        expect(boards(s, 1, b -> b.solvedAs(2, 100)), "but magic=false");
        expect(boards(s, 0, b -> new SeatBoard(b.grid(), b.version(), b.filled(), b.lines(), 3, b.elapsedMs(), null)),
                "places not 1..k");
        expect(boards(s, 0, b -> new SeatBoard(b.grid(), b.version(), b.filled(), b.lines(), 1, 999_999, null)),
                "outside the limit");
        expect(boards(s, 1, b -> b.withPace(s.board(2).pace())), "human seat 1 has a bot pace");
        expect(boards(s, 2, b -> b.withGrid(b.grid().with(1, 7), 0)), "plan");
        expect(boards(s, 2, b -> new SeatBoard(b.grid(), 3, b.filled(), b.lines(), 0, -1, b.pace())), "has a version");
        expect(s.withFirstSolvedAt(-1), "firstSolvedAt");
        expect(new StarSquareState(s.puzzles(), s.index(), Phase.RESULT, s.phaseStartedAt(), s.firstSolvedAt(),
                s.boards(), s.deserted(), s.outcomes(), s.matchSeed(), null), "result phase must follow");
        expect(new StarSquareState(s.puzzles(), s.index(), Phase.ENDED, s.phaseStartedAt(), s.firstSolvedAt(),
                s.boards(), s.deserted(), s.outcomes(), s.matchSeed(), null), "ended without result");
        expect(new StarSquareState(s.puzzles(), s.index(), Phase.ENDED, s.phaseStartedAt(), s.firstSolvedAt(),
                s.boards(), s.deserted(), s.outcomes(), s.matchSeed(),
                new MatchResult(MatchResult.EndReason.FINISHED, List.of())), "recomputed");
        expect(new StarSquareState(List.of(new PuzzleSpec(Band.THREE_EASY, CLUES, Grid.of(2, 9, 4, 7, 5, 3, 6, 1, 8))),
                0, s.phase(), s.phaseStartedAt(), s.firstSolvedAt(), s.boards(), s.deserted(), s.outcomes(),
                s.matchSeed(), null), "solution is not a magic square");
        expect(s.withDeserted(List.of(9)), "deserted seat out of range");
        assertThatThrownBy(() -> StarSquareInvariantChecker.check(s.withDeserted(List.of(9)), engine))
                .isInstanceOf(IllegalStateException.class);
    }

    private void expect(StarSquareState broken, String fragment) {
        assertThat(StarSquareInvariantChecker.violations(broken, engine)).anyMatch(v -> v.contains(fragment));
    }
}
