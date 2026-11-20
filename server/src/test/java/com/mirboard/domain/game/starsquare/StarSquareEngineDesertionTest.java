package com.mirboard.domain.game.starsquare;

import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU_CLUES;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.engine;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.loShuPuzzles;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.playing;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.sync;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.types;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.starsquare.StarSquareEngine.Desertion.Outcome;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.invariant.StarSquareInvariantChecker;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.Phase;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.SeatResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** `docs/rules-starsquare.md` §12.1 — 탈주. */
class StarSquareEngineDesertionTest {

    @Test
    void the_last_seat_standing_wins_without_scoring_the_open_puzzle() {
        StarSquareEngine e = engine(2, Set.of(), 3, 300);
        StarSquareState s = playing(e, loShuPuzzles(3));
        var d = e.desert(s, 1, s.phaseStartedAt() + 1_000);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        assertThat(types(d.events())).containsExactly("SEAT_DESERTED", "MATCH_ENDED");
        MatchResult result = d.newState().result();
        assertThat(result.reason()).isEqualTo(MatchResult.EndReason.LAST_SEAT);
        assertThat(result.winners()).containsExactly(0);
        assertThat(d.newState().outcomes()).isEmpty();
        StarSquareInvariantChecker.check(d.newState(), e);
    }

    @Test
    void no_humans_left_ends_the_match() {
        StarSquareEngine solo = engine(1, Set.of(), 1, 300);
        StarSquareState s = playing(solo, loShuPuzzles(1));
        var d = solo.desert(s, 0, s.phaseStartedAt());
        assertThat(d.newState().result().reason()).isEqualTo(MatchResult.EndReason.NO_HUMANS);

        StarSquareEngine withBots = engine(3, Set.of(1, 2), 1, 300);
        StarSquareState b = playing(withBots, loShuPuzzles(1));
        var d2 = withBots.desert(b, 0, b.phaseStartedAt());
        assertThat(d2.newState().result().reason()).isEqualTo(MatchResult.EndReason.NO_HUMANS);
        StarSquareInvariantChecker.check(d2.newState(), withBots);
    }

    @Test
    void deserting_the_only_unsolved_seat_ends_the_puzzle_and_a_solver_who_left_drops_to_the_bottom() {
        StarSquareEngine e = engine(4, Set.of(), 2, 300);
        StarSquareState s = playing(e, loShuPuzzles(2));
        long start = s.phaseStartedAt();
        s = e.apply(s, 0, sync(0, 1, LO_SHU.toArray()), start + 1_000).newState(); // 좌석 0 1번째
        s = e.apply(s, 1, sync(0, 1, LO_SHU.toArray()), start + 2_000).newState(); // 좌석 1 2번째
        s = e.apply(s, 2, sync(0, 1, LO_SHU.toArray()), start + 2_200).newState(); // 좌석 2 3번째
        s = e.desert(s, 0, start + 2_500).newState();                                 // 1번째가 나감
        var d = e.desert(s, 3, start + 3_000);                                        // 마지막 미완성이 나감
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_CONTINUES);
        assertThat(types(d.events())).containsExactly("SEAT_DESERTED", "PUZZLE_ENDED");
        var ended = (StarSquareEvent.PuzzleEnded) d.events().get(1);
        assertThat(ended.reason()).isEqualTo(PuzzleOutcome.EndReason.ALL_SOLVED);
        // 남은 좌석 1·2: 좌석 1 이 1위로 다시 매겨진다(완성 순서 2 → 1위), 좌석 2 는 2위. 탈주 0·3 은 3위·0점
        assertThat(ended.results()).extracting(SeatResult::rank).containsExactly(3, 1, 2, 3);
        assertThat(ended.results()).extracting(SeatResult::points).containsExactly(0, 3, 2, 0);
        assertThat(ended.results()).extracting(SeatResult::deserted).containsExactly(true, false, false, true);
        assertThat(d.newState().phase()).isEqualTo(Phase.RESULT);
        StarSquareInvariantChecker.check(d.newState(), e);

        // 다음 퍼즐 — 탈주자 판도 새로(단서)지만 대기 좌석이 아니고, 끝나면 0점 최하위
        StarSquareState next = e.onTimer(d.newState(), d.newState().phaseStartedAt() + StarSquareEngine.RESULT_MS)
                .newState();
        assertThat(next.board(0).grid()).isEqualTo(LO_SHU_CLUES);
        assertThat(e.pendingSeats(next)).containsExactly(1, 2);
        var end = e.onTimer(next, next.phaseStartedAt() + 300_000);
        var last = (StarSquareEvent.MatchEnded) end.events().get(end.events().size() - 1);
        assertThat(last.placements()).filteredOn(MatchResult.Placement::deserted)
                .extracting(MatchResult.Placement::rank).containsOnly(3);
        StarSquareInvariantChecker.check(end.newState(), e);
    }

    @Test
    void desertion_during_results_continues_and_repeat_or_late_desertion_is_not_applicable() {
        StarSquareEngine e = engine(3, Set.of(), 2, 300);
        StarSquareState s = playing(e, loShuPuzzles(2));
        StarSquareState result = e.onTimer(s, s.phaseStartedAt() + 300_000).newState();
        assertThat(result.phase()).isEqualTo(Phase.RESULT);
        var d = e.desert(result, 2, result.phaseStartedAt() + 1_000);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_CONTINUES);
        assertThat(d.newState().phase()).isEqualTo(Phase.RESULT);
        assertThat(e.desert(d.newState(), 2, result.phaseStartedAt() + 2_000).outcome())
                .isEqualTo(Outcome.NOT_APPLICABLE);
        StarSquareState over = e.desert(d.newState(), 1, result.phaseStartedAt() + 2_000).newState();
        assertThat(over.ended()).isTrue();
        assertThat(e.desert(over, 0, result.phaseStartedAt() + 3_000).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(e.desert(over, 7, result.phaseStartedAt() + 3_000).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }
}
