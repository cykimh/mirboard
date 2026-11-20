package com.mirboard.domain.game.starsquare;

import com.mirboard.domain.game.starsquare.action.StarSquareAction;
import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.puzzle.Band;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 별자리 수판 테스트 공용 판. */
final class StarSquareFixtures {

    static final long T0 = 1_000_000L;

    /** 2 7 6 / 9 5 1 / 4 3 8 — 낙서 마방진. */
    static final Grid LO_SHU = Grid.of(2, 7, 6, 9, 5, 1, 4, 3, 8);

    /** 단서 2(왼쪽 위)·1(가운데 오른쪽) — 같은 줄에 없어 줄 채우기로는 하나도 안 풀린다. */
    static final Grid LO_SHU_CLUES = Grid.of(2, 0, 0, 0, 0, 1, 0, 0, 0);

    static PuzzleSpec loShuPuzzle() {
        return new PuzzleSpec(Band.THREE_EASY, LO_SHU_CLUES, LO_SHU);
    }

    static List<PuzzleSpec> loShuPuzzles(int count) {
        return new ArrayList<>(Collections.nCopies(count, loShuPuzzle()));
    }

    static StarSquareEngine engine(int seats, Set<Integer> bots, int puzzles, int limitSeconds) {
        return new StarSquareEngine(new StarSquareConfig(seats, bots, puzzles, Difficulty.EASY, BotLevel.NORMAL,
                limitSeconds));
    }

    /** 준비를 지나 첫 퍼즐이 시작된 상태(시각 {@code T0 + INTRO_MS}). */
    static StarSquareState playing(StarSquareEngine engine, List<PuzzleSpec> puzzles) {
        StarSquareState intro = engine.start(puzzles, 42L, T0).newState();
        return engine.onTimer(intro, T0 + StarSquareEngine.INTRO_MS).newState();
    }

    static StarSquareAction.Sync sync(StarSquareState state, long version, Grid grid) {
        return new StarSquareAction.Sync(state.index(), version, grid.toList());
    }

    static StarSquareAction.Sync sync(int puzzle, long version, int... values) {
        List<Integer> list = new ArrayList<>();
        for (int v : values) {
            list.add(v);
        }
        return new StarSquareAction.Sync(puzzle, version, list);
    }

    static List<String> types(List<StarSquareEvent> events) {
        return events.stream().map(StarSquareEvent::envelopeType).toList();
    }

    private StarSquareFixtures() {
    }
}
