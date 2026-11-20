package com.mirboard.domain.game.starsquare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.mirboard.domain.game.starsquare.action.StarSquareAction;
import com.mirboard.domain.game.starsquare.action.StarSquareActionRejectedException;
import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.invariant.StarSquareInvariantChecker;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleBank;
import com.mirboard.domain.game.starsquare.puzzle.PuzzlePicker;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.SeatResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper.PublicView;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper.SeatView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `docs/rules-starsquare.md` §15 — 사람 흉내(무작위 시각에 무작위 판·가끔 정답 판·가끔 잘못된 판)와 봇을 섞고 무작위 탈주·시간 진행을 넣은
 * 매치가 인원별 1,000판 모두 끝나고, 매 전이 뒤 불변식을 지키며, 공개 이벤트만 듣는 클라가 그린 테이블이 엔진의 공개 뷰와 같다.
 */
class StarSquareMatchSimulationTest {

    private static final int MATCHES = 1_000;
    private static final PuzzleBank BANK = PuzzleBank.fromClasspath();

    record Stats(int matches, Map<MatchResult.EndReason, Integer> reasons, Map<PuzzleOutcome.EndReason, Integer> puzzleEnds,
                 int puzzles, int humanSolves, int botSolves, int transitions, int rejections) {
    }

    @ParameterizedTest(name = "{0}인 1,000매치")
    @ValueSource(ints = {1, 2, 3, 4})
    @Timeout(180)
    void random_matches_always_end_and_keep_every_invariant(int seats) {
        Stats stats = run(seats, MATCHES, 0x5A0000L + seats, 0.003);
        assertThat(stats.matches()).isEqualTo(MATCHES);
        assertThat(stats.reasons().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(MATCHES);
        assertThat(stats.humanSolves()).isPositive();
        if (seats > 1) {
            assertThat(stats.botSolves()).isPositive();
            assertThat(stats.puzzleEnds().getOrDefault(PuzzleOutcome.EndReason.CLOSED, 0)).isPositive();
        }
        assertThat(stats.puzzleEnds().getOrDefault(PuzzleOutcome.EndReason.ALL_SOLVED, 0)).isPositive();
        assertThat(stats.puzzleEnds().getOrDefault(PuzzleOutcome.EndReason.TIME_UP, 0)).isPositive();
        System.out.printf("starsquare sim %d seats: %d matches %s puzzles=%d %s humanSolves=%d botSolves=%d "
                        + "transitions=%d rejections=%d%n", seats, stats.matches(), stats.reasons(), stats.puzzles(),
                stats.puzzleEnds(), stats.humanSolves(), stats.botSolves(), stats.transitions(), stats.rejections());
    }

    @Test
    @Timeout(60)
    void the_same_seed_replays_the_same_matches() {
        assertThat(run(3, 50, 99L, 0.01)).isEqualTo(run(3, 50, 99L, 0.01));
    }

    // ---- 시뮬레이터 ----

    /** 사람 좌석 하나의 흉내 — 퍼즐마다 완성할 시각(또는 영영 못 함)과 다음 보낼 시각. */
    private static final class Human {
        long version;
        long nextAt;
        long solveAt;
    }

    private static Stats run(int seats, int matches, long seed, double desertRate) {
        SplittableRandom rng = new SplittableRandom(seed);
        Map<MatchResult.EndReason, Integer> reasons = new EnumMap<>(MatchResult.EndReason.class);
        Map<PuzzleOutcome.EndReason, Integer> puzzleEnds = new EnumMap<>(PuzzleOutcome.EndReason.class);
        int puzzles = 0;
        int humanSolves = 0;
        int botSolves = 0;
        int transitions = 0;
        int rejections = 0;
        for (int m = 0; m < matches; m++) {
            Set<Integer> bots = new HashSet<>();
            int human = rng.nextInt(seats);
            for (int s = 0; s < seats; s++) {
                if (s != human && rng.nextDouble() < 0.4) {
                    bots.add(s);
                }
            }
            Difficulty difficulty = Difficulty.values()[rng.nextInt(3)];
            int count = new int[] {1, 3, 5}[rng.nextInt(3)];
            BotLevel level = BotLevel.values()[rng.nextInt(3)];
            int limit = new int[] {20, 60, 180, 0}[rng.nextInt(4)];
            StarSquareEngine engine = new StarSquareEngine(new StarSquareConfig(seats, bots, count, difficulty, level,
                    limit));
            List<PuzzleSpec> picked = PuzzlePicker.pick(BANK, difficulty, count, rng);
            long now = 0;
            StarSquareState state = engine.start(picked, rng.nextLong(), now).newState();
            Mirror mirror = new Mirror(StarSquareStateMapper.toPublicView(state, 0));
            Human[] humans = new Human[seats];
            for (int s = 0; s < seats; s++) {
                humans[s] = bots.contains(s) ? null : new Human();
            }
            StarSquareInvariantChecker.check(state, engine);
            int guard = 0;
            while (!state.ended()) {
                if (++guard > 50_000) {
                    fail("match did not end: " + state);
                }
                long timerAt = now + engine.timer(state, now).orElseThrow();
                int actor = -1;
                long actorAt = Long.MAX_VALUE;
                for (int s : engine.pendingSeats(state)) {
                    if (humans[s].nextAt < actorAt) {
                        actorAt = humans[s].nextAt;
                        actor = s;
                    }
                }
                List<StarSquareEvent> events;
                if (actor >= 0 && actorAt < timerAt) {
                    now = Math.max(now, actorAt);
                    Human h = humans[actor];
                    h.nextAt = now + 300 + rng.nextInt(6_000);
                    if (rng.nextDouble() < desertRate) {
                        var d = engine.desert(state, actor, now);
                        events = d.events();
                        state = d.newState();
                    } else {
                        Attempt attempt = attempt(state, actor, h, now, rng);
                        try {
                            var r = engine.apply(state, actor, attempt.action(), now);
                            if (attempt.mustFail()) {
                                fail("bad sync accepted: " + attempt.action());
                            }
                            h.version = Math.max(h.version, attempt.action().version());
                            events = r.events();
                            state = r.newState();
                        } catch (StarSquareActionRejectedException rejected) {
                            rejections++;
                            if (!attempt.mustFail()) {
                                fail("good sync rejected " + rejected.reason() + ": " + attempt.action());
                            }
                            continue;
                        }
                    }
                } else {
                    now = Math.max(now, timerAt);
                    var r = engine.onTimer(state, now);
                    events = r.events();
                    state = r.newState();
                    if (events.isEmpty()) {
                        fail("timer fired with nothing due at " + now + ": " + state);
                    }
                }
                transitions++;
                for (StarSquareEvent ev : events) {
                    if (ev instanceof StarSquareEvent.PuzzleStarted started) {
                        puzzles++;
                        long start = now;
                        long limitMs = started.limitMs();
                        for (Human h : humans) {
                            if (h != null) {
                                h.version = 0;
                                h.nextAt = start + 200 + rng.nextInt(3_000);
                                h.solveAt = rng.nextDouble() < 0.7 ? start + 1_000 + (long) (rng.nextDouble() * limitMs * 1.2)
                                        : Long.MAX_VALUE;
                            }
                        }
                    } else if (ev instanceof StarSquareEvent.Solved solved) {
                        if (bots.contains(solved.seat())) {
                            botSolves++;
                        } else {
                            humanSolves++;
                        }
                    } else if (ev instanceof StarSquareEvent.PuzzleEnded ended) {
                        puzzleEnds.merge(ended.reason(), 1, Integer::sum);
                    }
                }
                StarSquareInvariantChecker.check(state, engine);
                mirror.apply(events);
                mirror.assertMatches(StarSquareStateMapper.toPublicView(state, engine.remainingMillis(state, now)));
            }
            reasons.merge(state.result().reason(), 1, Integer::sum);
        }
        return new Stats(matches, reasons, puzzleEnds, puzzles, humanSolves, botSolves, transitions, rejections);
    }

    private record Attempt(StarSquareAction.Sync action, boolean mustFail) {
    }

    /** 사람 한 번의 보내기. 대부분 정상(부분 판·완성 판), 가끔 낡은 버전·잘못된 판·단서 바꿈. */
    private static Attempt attempt(StarSquareState state, int seat, Human h, long now, SplittableRandom rng) {
        PuzzleSpec p = state.puzzle();
        Grid clues = p.clues();
        int nn = clues.size();
        double roll = rng.nextDouble();
        long next = h.version + 1 + rng.nextInt(3);
        if (roll < 0.04 && h.version > 0) {
            return new Attempt(new StarSquareAction.Sync(state.index(), h.version - 1, clues.toList()), true);
        }
        if (roll < 0.06) {
            List<Integer> bad = new ArrayList<>(clues.toList());
            bad.set(rng.nextInt(nn), nn + 1);
            return new Attempt(new StarSquareAction.Sync(state.index(), next, bad), true);
        }
        if (roll < 0.08) {
            int[] changed = clues.toArray();
            for (int i = 0; i < nn; i++) {
                if (changed[i] != 0) {
                    changed[i] = changed[i] == 1 ? 2 : 1;
                    break;
                }
            }
            return new Attempt(new StarSquareAction.Sync(state.index(), next, Grid.of(changed).toList()), true);
        }
        if (roll < 0.09 && state.index() > 0) {
            return new Attempt(new StarSquareAction.Sync(state.index() - 1, next, clues.toList()), true);
        }
        if (now >= h.solveAt) {
            return new Attempt(new StarSquareAction.Sync(state.index(), next, p.solution().toList()), false);
        }
        int[] cells = clues.toArray();
        for (int i = 0; i < nn; i++) {
            if (cells[i] == 0) {
                double r = rng.nextDouble();
                if (r < 0.45) {
                    cells[i] = p.solution().get(i);
                } else if (r < 0.6) {
                    cells[i] = 1 + rng.nextInt(nn); // 틀리거나 중복일 수 있는 값
                }
            }
        }
        return new Attempt(new StarSquareAction.Sync(state.index(), next, Grid.of(cells).toList()), false);
    }

    // ---- 이벤트만 듣는 클라 ----

    private static final class Mirror {
        String phase;
        int index;
        int count;
        int size;
        int magicSum;
        Grid clues;
        boolean closing;
        List<SeatView> seats;
        PuzzleOutcome lastPuzzle;
        MatchResult result;

        Mirror(PublicView initial) {
            phase = initial.phase();
            index = initial.puzzleIndex();
            count = initial.puzzleCount();
            size = initial.size();
            magicSum = initial.magicSum();
            clues = initial.clues();
            closing = initial.closing();
            seats = new ArrayList<>(initial.seats());
            lastPuzzle = initial.lastPuzzle();
            result = initial.result();
        }

        void apply(List<StarSquareEvent> events) {
            for (StarSquareEvent ev : events) {
                switch (ev) {
                    case StarSquareEvent.PuzzleStarted e -> {
                        phase = "PLAYING";
                        index = e.index();
                        count = e.count();
                        size = e.size();
                        magicSum = e.magicSum();
                        clues = e.clues();
                        closing = false;
                        lastPuzzle = null;
                        for (int s = 0; s < seats.size(); s++) {
                            SeatView v = seats.get(s);
                            seats.set(s, new SeatView(s, e.filled(), e.lines(), false, 0, -1, v.points(), v.firsts(),
                                    v.deserted()));
                        }
                    }
                    case StarSquareEvent.Progress e -> {
                        SeatView v = seats.get(e.seat());
                        seats.set(e.seat(), new SeatView(e.seat(), e.filled(), e.lines(), v.solved(), v.solvePlace(),
                                v.elapsedMs(), v.points(), v.firsts(), v.deserted()));
                    }
                    case StarSquareEvent.Solved e -> {
                        SeatView v = seats.get(e.seat());
                        seats.set(e.seat(), new SeatView(e.seat(), size * size, 2 * size + 2, true, e.place(),
                                e.elapsedMs(), v.points(), v.firsts(), v.deserted()));
                    }
                    case StarSquareEvent.Closing e -> closing = true;
                    case StarSquareEvent.PuzzleEnded e -> {
                        phase = "RESULT";
                        closing = false;
                        lastPuzzle = new PuzzleOutcome(e.index(), e.reason(), e.results(), e.grids(), e.solution());
                        for (SeatResult r : e.results()) {
                            SeatView v = seats.get(r.seat());
                            int firsts = v.firsts() + (r.rank() == 1 && !r.deserted() ? 1 : 0);
                            seats.set(r.seat(), new SeatView(r.seat(), v.filled(), v.lines(), v.solved(),
                                    v.solvePlace(), v.elapsedMs(), e.totals().get(r.seat()), firsts, v.deserted()));
                        }
                    }
                    case StarSquareEvent.SeatDeserted e -> {
                        SeatView v = seats.get(e.seat());
                        seats.set(e.seat(), new SeatView(e.seat(), v.filled(), v.lines(), v.solved(), v.solvePlace(),
                                v.elapsedMs(), v.points(), v.firsts(), true));
                    }
                    case StarSquareEvent.MatchEnded e -> {
                        phase = "ENDED";
                        closing = false;
                        result = new MatchResult(e.reason(), e.placements());
                    }
                }
            }
        }

        void assertMatches(PublicView view) {
            assertThat(phase).isEqualTo(view.phase());
            assertThat(index).isEqualTo(view.puzzleIndex());
            assertThat(count).isEqualTo(view.puzzleCount());
            assertThat(size).isEqualTo(view.size());
            assertThat(magicSum).isEqualTo(view.magicSum());
            assertThat(clues).isEqualTo(view.clues());
            assertThat(closing).isEqualTo(view.closing());
            assertThat(seats).isEqualTo(view.seats());
            assertThat(lastPuzzle).isEqualTo(view.lastPuzzle());
            assertThat(result).isEqualTo(view.result());
        }
    }
}
