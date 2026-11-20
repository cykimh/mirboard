package com.mirboard.domain.game.starsquare;

import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU_CLUES;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.T0;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.engine;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.loShuPuzzles;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.playing;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.sync;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.starsquare.action.RejectionReason;
import com.mirboard.domain.game.starsquare.action.StarSquareAction;
import com.mirboard.domain.game.starsquare.action.StarSquareActionRejectedException;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.invariant.StarSquareInvariantChecker;
import com.mirboard.domain.game.starsquare.puzzle.Band;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.MagicCheck;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleBank;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import com.mirboard.domain.game.starsquare.puzzle.Solver;
import com.mirboard.domain.game.starsquare.state.Phase;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.SeatResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** `docs/rules-starsquare.md` §5~§8 — 준비·SYNC·완성·마감·결과 보기·매치 끝·거절. */
class StarSquareEngineTest {

    @Test
    void the_match_starts_hidden_for_three_seconds_then_shows_the_same_puzzle_to_everyone() {
        StarSquareEngine e = engine(2, Set.of(), 1, 300);
        var start = e.start(loShuPuzzles(1), 7L, T0);
        StarSquareState intro = start.newState();
        assertThat(start.events()).isEmpty();
        assertThat(intro.phase()).isEqualTo(Phase.INTRO);
        assertThat(e.pendingSeats(intro)).isEmpty();
        assertThat(e.timer(intro, T0)).hasValue(StarSquareEngine.INTRO_MS);
        assertReject(() -> e.apply(intro, 0, sync(0, 1, LO_SHU.toArray()), T0 + 10), RejectionReason.PUZZLE_CLOSED);
        // 준비가 끝나기 전 타이머는 아무것도 안 한다
        assertThat(e.onTimer(intro, T0 + 2_999).events()).isEmpty();

        var started = e.onTimer(intro, T0 + 3_100);
        assertThat(types(started.events())).containsExactly("PUZZLE_STARTED");
        var ev = (StarSquareEvent.PuzzleStarted) started.events().get(0);
        assertThat(ev.clues()).isEqualTo(LO_SHU_CLUES);
        assertThat(ev.size()).isEqualTo(3);
        assertThat(ev.magicSum()).isEqualTo(15);
        assertThat(ev.limitMs()).isEqualTo(300_000);
        assertThat(ev.filled()).isEqualTo(2);
        assertThat(ev.lines()).isZero();
        StarSquareState s = started.newState();
        assertThat(s.phase()).isEqualTo(Phase.PLAYING);
        assertThat(s.phaseStartedAt()).isEqualTo(T0 + 3_100);
        assertThat(e.pendingSeats(s)).containsExactly(0, 1);
        StarSquareInvariantChecker.check(s, e);
    }

    @Test
    void sync_updates_the_board_and_announces_only_changed_progress() {
        StarSquareEngine e = engine(2, Set.of(), 1, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long t = s.phaseStartedAt() + 1_000;
        // 2 7 6 / . . 1 / . . . — 윗줄 맞음
        var r1 = e.apply(s, 0, sync(0, 1, 2, 7, 6, 0, 0, 1, 0, 0, 0), t);
        assertThat(r1.events()).containsExactly(new StarSquareEvent.Progress(0, 4, 1));
        assertThat(r1.newState().board(0).version()).isEqualTo(1);
        // 새 버전이지만 진행도가 같다 — 이벤트 없음(버전만 오른다)
        var r2 = e.apply(r1.newState(), 0, sync(0, 2, 2, 7, 6, 0, 0, 1, 0, 0, 0), t + 100);
        assertThat(r2.events()).isEmpty();
        assertThat(r2.newState().board(0).version()).isEqualTo(2);
        // 중복 숫자는 세지 않는다 — 7 을 두 번
        var r3 = e.apply(r2.newState(), 0, sync(0, 3, 2, 7, 6, 0, 0, 1, 0, 7, 0), t + 200);
        assertThat(r3.events()).containsExactly(new StarSquareEvent.Progress(0, 3, 0));
        // 같은 버전·같은 판 = 받되 무변화
        var same = e.apply(r3.newState(), 0, sync(0, 3, 2, 7, 6, 0, 0, 1, 0, 7, 0), t + 300);
        assertThat(same.events()).isEmpty();
        assertThat(same.newState()).isEqualTo(r3.newState());
        StarSquareInvariantChecker.check(r3.newState(), e);
    }

    @Test
    void a_magic_square_solves_closes_the_puzzle_in_30_seconds_and_the_last_solver_ends_it() {
        StarSquareEngine e = engine(2, Set.of(), 2, 300);
        StarSquareState s = playing(e, loShuPuzzles(2));
        long start = s.phaseStartedAt();
        var first = e.apply(s, 1, sync(0, 1, LO_SHU.toArray()), start + 40_000);
        assertThat(first.events()).containsExactly(new StarSquareEvent.Solved(1, 1, 40_000),
                new StarSquareEvent.Closing(30_000));
        StarSquareState closing = first.newState();
        assertThat(e.deadline(closing)).isEqualTo(start + 70_000);
        assertThat(e.pendingSeats(closing)).containsExactly(0);
        assertThat(e.timer(closing, start + 50_000)).hasValue(20_000);
        assertReject(() -> e.apply(closing, 1, sync(0, 2, LO_SHU.toArray()), start + 41_000),
                RejectionReason.ALREADY_SOLVED);

        var second = e.apply(closing, 0, sync(0, 1, LO_SHU.toArray()), start + 55_000);
        assertThat(types(second.events())).containsExactly("SOLVED", "PUZZLE_ENDED");
        var ended = (StarSquareEvent.PuzzleEnded) second.events().get(1);
        assertThat(ended.reason()).isEqualTo(PuzzleOutcome.EndReason.ALL_SOLVED);
        assertThat(ended.results()).extracting(SeatResult::rank).containsExactly(2, 1);
        assertThat(ended.results()).extracting(SeatResult::points).containsExactly(0, 1);
        assertThat(ended.totals()).containsExactly(0, 1);
        assertThat(ended.solution()).isEqualTo(LO_SHU);
        assertThat(ended.grids()).containsExactly(LO_SHU, LO_SHU);
        assertThat(ended.nextInMs()).isEqualTo(StarSquareEngine.RESULT_MS);
        StarSquareState result = second.newState();
        assertThat(result.phase()).isEqualTo(Phase.RESULT);
        assertThat(e.pendingSeats(result)).isEmpty();
        assertReject(() -> e.apply(result, 0, sync(0, 9, LO_SHU.toArray()), start + 56_000),
                RejectionReason.PUZZLE_CLOSED);
        StarSquareInvariantChecker.check(result, e);

        // 결과 보기 8초 뒤 다음 퍼즐 — 판·버전이 새로
        var next = e.onTimer(result, start + 55_000 + StarSquareEngine.RESULT_MS);
        assertThat(types(next.events())).containsExactly("PUZZLE_STARTED");
        StarSquareState p2 = next.newState();
        assertThat(p2.index()).isEqualTo(1);
        assertThat(p2.board(0).grid()).isEqualTo(LO_SHU_CLUES);
        assertThat(p2.board(0).version()).isZero();
        assertThat(p2.firstSolvedAt()).isEqualTo(-1);
        // 지난 퍼즐의 늦은 판은 낡은 것
        assertReject(() -> e.apply(p2, 0, sync(0, 5, LO_SHU.toArray()), p2.phaseStartedAt() + 1),
                RejectionReason.STALE_SYNC);
        StarSquareInvariantChecker.check(p2, e);
    }

    @Test
    void the_close_after_the_first_solve_ends_the_puzzle_and_ranks_the_rest_by_lines_then_cells() {
        StarSquareEngine e = engine(4, Set.of(), 1, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        s = e.apply(s, 2, sync(0, 1, LO_SHU.toArray()), start + 10_000).newState();
        // 좌석 0: 윗줄(줄 1, 칸 4) / 좌석 1: 칸만 3 / 좌석 3: 윗줄 + 칸 5
        s = e.apply(s, 0, sync(0, 1, 2, 7, 6, 0, 0, 1, 0, 0, 0), start + 11_000).newState();
        s = e.apply(s, 1, sync(0, 1, 2, 0, 0, 0, 0, 1, 0, 0, 8), start + 12_000).newState();
        s = e.apply(s, 3, sync(0, 1, 2, 7, 6, 0, 0, 1, 0, 0, 3), start + 13_000).newState();
        assertReject(sOf(s, e, 0, start + 40_000), RejectionReason.PUZZLE_CLOSED);

        assertThat(e.onTimer(s, start + 39_999).events()).isEmpty();
        var end = e.onTimer(s, start + 40_000);
        assertThat(types(end.events())).containsExactly("PUZZLE_ENDED", "MATCH_ENDED");
        var ended = (StarSquareEvent.PuzzleEnded) end.events().get(0);
        assertThat(ended.reason()).isEqualTo(PuzzleOutcome.EndReason.CLOSED);
        assertThat(ended.results()).extracting(SeatResult::rank).containsExactly(3, 4, 1, 2);
        assertThat(ended.results()).extracting(SeatResult::points).containsExactly(1, 0, 3, 2);
        assertThat(ended.nextInMs()).isZero();
        assertThat(end.newState().phase()).isEqualTo(Phase.ENDED);
        StarSquareInvariantChecker.check(end.newState(), e);
    }

    @Test
    void the_puzzle_limit_ends_an_unsolved_puzzle_with_ties_sharing_a_rank() {
        StarSquareEngine e = engine(3, Set.of(), 1, 120);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        s = e.apply(s, 0, sync(0, 1, 2, 7, 6, 0, 0, 1, 0, 0, 0), start + 1_000).newState();
        s = e.apply(s, 1, sync(0, 1, 2, 7, 6, 0, 0, 1, 0, 0, 0), start + 1_000).newState();
        assertThat(e.timer(s, start + 1_000)).hasValue(119_000);
        var end = e.onTimer(s, start + 120_000);
        var ended = (StarSquareEvent.PuzzleEnded) end.events().get(0);
        assertThat(ended.reason()).isEqualTo(PuzzleOutcome.EndReason.TIME_UP);
        assertThat(ended.results()).extracting(SeatResult::rank).containsExactly(1, 1, 3);
        assertThat(ended.results()).extracting(SeatResult::points).containsExactly(2, 2, 0);
        var matchEnd = (StarSquareEvent.MatchEnded) end.events().get(1);
        assertThat(matchEnd.placements()).extracting(p -> p.rank()).containsExactly(1, 1, 3);
        assertThat(end.newState().result().winners()).containsExactly(0, 1);
    }

    @Test
    void a_different_magic_square_that_matches_the_clues_also_solves() {
        PuzzleBank bank = PuzzleBank.fromClasspath();
        PuzzleSpec twoWay = bank.puzzles(Band.FOUR_NORMAL).stream()
                .filter(p -> Solver.count(p.clues(), 10, Long.MAX_VALUE).solutions() == 2)
                .findFirst().orElseThrow();
        List<Grid> both = new ArrayList<>();
        Solver.enumerate(4, g -> {
            if (twoWay.clues().nonZeroCellsMatch(g)) {
                both.add(g);
            }
        });
        Grid other = both.stream().filter(g -> !g.equals(twoWay.solution())).findFirst().orElseThrow();
        StarSquareEngine e = engine(1, Set.of(), 1, 300);
        StarSquareState s = playing(e, List.of(twoWay));
        var r = e.apply(s, 0, sync(s, 1, other), s.phaseStartedAt() + 5_000);
        assertThat(types(r.events())).containsExactly("SOLVED", "PUZZLE_ENDED", "MATCH_ENDED");
        StarSquareInvariantChecker.check(r.newState(), e);
    }

    @Test
    void a_solo_match_ends_each_puzzle_on_its_solve_without_a_close() {
        StarSquareEngine e = engine(1, Set.of(), 2, 300);
        StarSquareState s = playing(e, loShuPuzzles(2));
        var r = e.apply(s, 0, sync(0, 1, LO_SHU.toArray()), s.phaseStartedAt() + 12_345);
        assertThat(types(r.events())).containsExactly("SOLVED", "PUZZLE_ENDED");
        var ended = (StarSquareEvent.PuzzleEnded) r.events().get(1);
        assertThat(ended.results().get(0).points()).isZero();
        assertThat(ended.results().get(0).elapsedMs()).isEqualTo(12_345);
    }

    @Test
    void the_match_ranks_by_total_then_by_first_places() {
        StarSquareEngine e = engine(2, Set.of(), 3, 300);
        StarSquareState s = playing(e, loShuPuzzles(3));
        // 퍼즐 1: 좌석 0 1등 / 퍼즐 2: 좌석 1 1등 / 퍼즐 3: 아무도 못 함(동순위 1·1 → 둘 다 1점)
        s = solveInOrder(e, s, 0, 1);
        s = e.onTimer(s, s.phaseStartedAt() + StarSquareEngine.RESULT_MS).newState();
        s = solveInOrder(e, s, 1, 0);
        s = e.onTimer(s, s.phaseStartedAt() + StarSquareEngine.RESULT_MS).newState();
        var end = e.onTimer(s, s.phaseStartedAt() + 300_000);
        var matchEnd = (StarSquareEvent.MatchEnded) end.events().get(end.events().size() - 1);
        assertThat(matchEnd.placements()).extracting(p -> p.points()).containsExactly(2, 2);
        // 1등 횟수: 좌석 0 = 퍼즐 1 + 퍼즐 3(동순위 1위), 좌석 1 = 퍼즐 2 + 퍼즐 3 → 2 대 2 → 동순위
        assertThat(matchEnd.placements()).extracting(p -> p.rank()).containsExactly(1, 1);
        assertThat(matchEnd.placements()).extracting(p -> p.firsts()).containsExactly(2, 2);
        StarSquareInvariantChecker.check(end.newState(), e);
    }

    private static StarSquareState solveInOrder(StarSquareEngine e, StarSquareState s, int firstSeat, int secondSeat) {
        long start = s.phaseStartedAt();
        s = e.apply(s, firstSeat, sync(s.index(), 1, LO_SHU.toArray()), start + 1_000).newState();
        return e.apply(s, secondSeat, sync(s.index(), 1, LO_SHU.toArray()), start + 2_000).newState();
    }

    @Test
    void rejections_follow_the_documented_order_and_never_change_the_state() {
        StarSquareEngine e = engine(3, Set.of(2), 2, 300);
        StarSquareState s = playing(e, loShuPuzzles(2));
        long t = s.phaseStartedAt() + 1_000;
        StarSquareState base = e.apply(s, 0, sync(0, 5, 2, 7, 0, 0, 0, 1, 0, 0, 0), t).newState();
        assertReject(() -> e.apply(base, 0, null, t), RejectionReason.INVALID_ACTION);
        assertReject(() -> e.apply(base, 2, sync(0, 1, LO_SHU.toArray()), t), RejectionReason.NOT_IN_PLAY);
        assertReject(() -> e.apply(base, 9, sync(0, 1, LO_SHU.toArray()), t), RejectionReason.NOT_IN_PLAY);
        assertReject(() -> e.apply(base, 0, sync(1, 6, LO_SHU.toArray()), t), RejectionReason.BAD_GRID);
        assertReject(() -> e.apply(base, 0, sync(0, 6, 2, 7, 6), t), RejectionReason.BAD_GRID);
        assertReject(() -> e.apply(base, 0, sync(0, 6, 2, 7, 6, 9, 5, 1, 4, 3, 10), t), RejectionReason.BAD_GRID);
        assertReject(() -> e.apply(base, 0, sync(0, 6, 2, 7, 6, 9, 5, 1, 4, 3, -1), t), RejectionReason.BAD_GRID);
        assertReject(() -> e.apply(base, 0, new StarSquareAction.Sync(0, 6, null), t), RejectionReason.BAD_GRID);
        assertReject(() -> e.apply(base, 0, new StarSquareAction.Sync(0, 6,
                Arrays.asList(2, 7, 6, 9, 5, 1, 4, 3, null)), t), RejectionReason.BAD_GRID);
        assertReject(() -> e.apply(base, 0, sync(0, 6, 4, 9, 2, 3, 5, 7, 8, 1, 6), t), RejectionReason.CLUE_CHANGED);
        assertReject(() -> e.apply(base, 0, sync(0, 6, 0, 7, 6, 9, 5, 1, 4, 3, 8), t), RejectionReason.CLUE_CHANGED);
        assertReject(() -> e.apply(base, 0, sync(0, 4, LO_SHU.toArray()), t), RejectionReason.STALE_SYNC);
        assertReject(() -> e.apply(base, 0, sync(0, 5, LO_SHU.toArray()), t), RejectionReason.STALE_SYNC);
        // 마감이 지난 판(타이머가 아직 안 돌았어도)
        assertReject(() -> e.apply(base, 0, sync(0, 6, LO_SHU.toArray()), base.phaseStartedAt() + 300_000),
                RejectionReason.PUZZLE_CLOSED);
        // 탈주한 좌석
        StarSquareState deserted = e.desert(base, 1, t).newState();
        assertReject(() -> e.apply(deserted, 1, sync(0, 1, LO_SHU.toArray()), t), RejectionReason.NOT_IN_PLAY);
        // 끝난 매치
        StarSquareState over = e.desert(deserted, 0, t).newState();
        assertThat(over.ended()).isTrue();
        assertReject(() -> e.apply(over, 0, sync(0, 9, LO_SHU.toArray()), t), RejectionReason.MATCH_OVER);
        assertThat(RejectionReason.STALE_SYNC.quiet()).isTrue();
        assertThat(RejectionReason.BAD_GRID.quiet()).isFalse();
    }

    @Test
    void a_zero_limit_means_the_default_five_minutes_and_the_timer_is_not_reset_by_syncs() {
        StarSquareEngine e = engine(1, Set.of(), 1, 0);
        assertThat(e.config().limitSeconds()).isEqualTo(300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        for (int v = 1; v <= 5; v++) {
            s = e.apply(s, 0, sync(0, v, 2, v % 2 == 0 ? 7 : 0, 0, 0, 0, 1, 0, 0, 0), start + v * 10_000L).newState();
            assertThat(e.timer(s, start + v * 10_000L)).hasValue(300_000 - v * 10_000L);
        }
        assertThat(e.remainingMillis(s, start + 299_000)).isEqualTo(1_000);
        assertThat(e.fallbackAction(s, 0)).isEqualTo(new StarSquareAction.Sync(0, 5, s.board(0).grid().toList()));
        // 폴백은 받되 무변화
        assertThat(e.apply(s, 0, e.fallbackAction(s, 0), start + 60_000).newState()).isEqualTo(s);
        assertThat(e.legalActions(s, 0)).containsExactly(e.fallbackAction(s, 0));
        assertThat(MagicCheck.isMagic(LO_SHU, LO_SHU_CLUES)).isTrue();
    }

    private static Runnable sOf(StarSquareState s, StarSquareEngine e, int seat, long at) {
        return () -> e.apply(s, seat, sync(0, 99, LO_SHU.toArray()), at);
    }

    static void assertReject(Runnable call, RejectionReason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOf(StarSquareActionRejectedException.class)
                .satisfies(ex -> assertThat(((StarSquareActionRejectedException) ex).reason()).isEqualTo(reason));
    }
}
