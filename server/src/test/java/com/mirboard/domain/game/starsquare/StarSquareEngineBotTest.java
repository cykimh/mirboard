package com.mirboard.domain.game.starsquare;

import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.T0;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.loShuPuzzles;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.playing;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.sync;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.types;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.bot.BotPace;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.invariant.StarSquareInvariantChecker;
import com.mirboard.domain.game.starsquare.puzzle.Band;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import com.mirboard.domain.game.starsquare.puzzle.MagicCheck;
import com.mirboard.domain.game.starsquare.state.BotPlan;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.SeatResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** `docs/rules-starsquare.md` §10 — 봇 페이스: 계획 시각에 정답 칸을 채우고, 마지막 이정표가 완성이다. */
class StarSquareEngineBotTest {

    private static StarSquareEngine engine(int seats, Set<Integer> bots, BotLevel level, int limitSeconds) {
        return new StarSquareEngine(new StarSquareConfig(seats, bots, 1, Difficulty.EASY, level, limitSeconds));
    }

    @Test
    void a_bot_fills_solution_cells_milestone_by_milestone_and_solves_at_its_planned_time() {
        StarSquareEngine e = engine(2, Set.of(1), BotLevel.NORMAL, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        BotPlan pace = s.board(1).pace();
        assertThat(pace).isNotNull();
        assertThat(s.board(0).pace()).isNull();
        assertThat(pace.order()).hasSize(7);
        assertThat(pace.milestones()).isEqualTo(6);
        assertThat(e.pendingSeats(s)).containsExactly(0); // 봇은 대기 좌석이 아니다

        List<StarSquareEvent> events = new ArrayList<>();
        StarSquareState cur = s;
        for (int j = 1; j <= pace.milestones(); j++) {
            long at = start + pace.milestoneAt(j);
            assertThat(e.timer(cur, at)).hasValue(0);
            var r = e.onTimer(cur, at);
            events.addAll(r.events());
            cur = r.newState();
            StarSquareInvariantChecker.check(cur, e);
            assertThat(cur.board(1).grid().nonZeroCellsMatch(LO_SHU)).isTrue();
        }
        assertThat(types(events)).containsExactly("PROGRESS", "PROGRESS", "PROGRESS", "PROGRESS", "PROGRESS",
                "SOLVED", "CLOSING");
        assertThat(cur.board(1).grid()).isEqualTo(LO_SHU);
        assertThat(cur.board(1).elapsedMs()).isEqualTo(pace.solveAfterMs());
        // 봇의 첫 완성도 마감을 당긴다
        assertThat(e.deadline(cur)).isEqualTo(start + pace.solveAfterMs() + StarSquareEngine.CLOSE_AFTER_FIRST_MS);
    }

    @Test
    void overdue_milestones_are_processed_in_time_order_in_one_timer_call() {
        StarSquareEngine e = engine(3, Set.of(1, 2), BotLevel.HARD, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        long late = start + Math.max(s.board(1).pace().solveAfterMs(), s.board(2).pace().solveAfterMs()) + 500;
        var r = e.onTimer(s, late);
        assertThat(r.newState().board(1).solved()).isTrue();
        assertThat(r.newState().board(2).solved()).isTrue();
        // 먼저 계획된 봇이 먼저 완성 순서를 받는다
        int earlier = s.board(1).pace().solveAfterMs() <= s.board(2).pace().solveAfterMs() ? 1 : 2;
        assertThat(r.newState().board(earlier).solvePlace()).isEqualTo(1);
        StarSquareInvariantChecker.check(r.newState(), e);
    }

    @Test
    void a_bot_slower_than_the_limit_never_solves_and_is_ranked_by_what_it_filled() {
        StarSquareEngine e = engine(2, Set.of(1), BotLevel.EASY, 30);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        assertThat(s.board(1).pace().solveAfterMs()).isGreaterThan(30_000);
        var end = e.onTimer(s, start + 30_000);
        assertThat(end.newState().ended()).isTrue();
        var ended = (StarSquareEvent.PuzzleEnded) end.events().stream()
                .filter(ev -> ev instanceof StarSquareEvent.PuzzleEnded).findFirst().orElseThrow();
        assertThat(ended.reason()).isEqualTo(PuzzleOutcome.EndReason.TIME_UP);
        SeatResult bot = ended.results().get(1);
        assertThat(bot.solved()).isFalse();
        assertThat(bot.filled()).isGreaterThan(2); // 단서 2 + 채운 칸
        // 공개된 봇 판은 단서 + 정답 칸
        assertThat(ended.grids().get(1).nonZeroCellsMatch(LO_SHU)).isTrue();
        StarSquareInvariantChecker.check(end.newState(), e);
    }

    @Test
    void a_bot_never_records_an_earlier_time_than_a_solver_processed_before_it() {
        StarSquareEngine e = engine(3, Set.of(1), BotLevel.NORMAL, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        BotPlan pace = s.board(1).pace();
        // 봇의 마지막 이정표 바로 전까지 진행
        StarSquareState cur = s;
        for (int j = 1; j < pace.milestones(); j++) {
            cur = e.onTimer(cur, start + pace.milestoneAt(j)).newState();
        }
        // 사람이 봇 완성 계획 시각 100ms 뒤에 처리되고(타이머보다 먼저), 그다음 봇
        long humanAt = start + pace.solveAfterMs() + 100;
        cur = e.apply(cur, 0, sync(0, 1, LO_SHU.toArray()), humanAt).newState();
        cur = e.onTimer(cur, humanAt + 50).newState();
        assertThat(cur.board(0).solvePlace()).isEqualTo(1);
        assertThat(cur.board(1).solvePlace()).isEqualTo(2);
        assertThat(cur.board(1).elapsedMs()).isGreaterThanOrEqualTo(cur.board(0).elapsedMs());
        StarSquareInvariantChecker.check(cur, e);
    }

    @Test
    void the_pace_is_deterministic_and_stronger_levels_are_faster() {
        var clues = StarSquareFixtures.LO_SHU_CLUES;
        BotPlan a = BotPace.plan(9L, 2, 1, Band.FOUR_NORMAL, BotLevel.NORMAL, clues);
        BotPlan b = BotPace.plan(9L, 2, 1, Band.FOUR_NORMAL, BotLevel.NORMAL, clues);
        assertThat(a).isEqualTo(b);
        assertThat(BotPace.plan(9L, 3, 1, Band.FOUR_NORMAL, BotLevel.NORMAL, clues)).isNotEqualTo(a);
        // 같은 흔들림(같은 시드·퍼즐·좌석)이면 강도 배수만 다르다
        long easy = BotPace.plan(9L, 2, 1, Band.FIVE_HARD, BotLevel.EASY, clues).solveAfterMs();
        long normal = BotPace.plan(9L, 2, 1, Band.FIVE_HARD, BotLevel.NORMAL, clues).solveAfterMs();
        long hard = BotPace.plan(9L, 2, 1, Band.FIVE_HARD, BotLevel.HARD, clues).solveAfterMs();
        assertThat(easy).isGreaterThan(normal);
        assertThat(normal).isGreaterThan(hard);
        for (Band band : Band.values()) {
            for (int seat = 0; seat < 4; seat++) {
                long t = BotPace.plan(T0, seat, seat, band, BotLevel.NORMAL, clues).solveAfterMs();
                double base = BotPace.baseMillis(band) * BotLevel.NORMAL.factor();
                assertThat(t).isBetween(Math.round(base * BotPace.JITTER_MIN) - 1, Math.round(base * BotPace.JITTER_MAX) + 1);
            }
        }
        assertThat(BotLevel.fromOption("HARD")).isEqualTo(BotLevel.HARD);
        assertThat(BotLevel.fromOption("NOPE")).isEqualTo(BotLevel.NORMAL);
        assertThat(MagicCheck.isMagic(LO_SHU)).isTrue();
    }
}
