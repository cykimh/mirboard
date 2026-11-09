package com.mirboard.domain.game.omok.invariant;

import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static com.mirboard.domain.game.omok.OmokStates.solo;
import static com.mirboard.domain.game.omok.OmokStates.teams;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.MatchResult;
import com.mirboard.domain.game.omok.state.Move;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 통과 1건 + 고의 위반마다 1건 — 규칙 하나를 끄면 그 케이스만 실패해야 한다. */
class OmokInvariantCheckerTest {

    private static void violates(OmokState s, String fragment) {
        assertThatThrownBy(() -> OmokInvariantChecker.check(s))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fragment);
    }

    private static OmokState with(OmokState s, List<Move> moves, int turn, List<Integer> timeouts, MatchResult r) {
        return new OmokState(s.mode(), s.rule(), s.blackTeam(), s.firstSeat(), moves, turn, timeouts, r);
    }

    private static final OmokState FIVE_MOVES = play(solo(OmokRule.RENJU), p(7, 7), p(0, 0), p(8, 7), p(0, 2), p(9, 7));

    @Test
    void real_states_pass() {
        assertThatCode(() -> OmokInvariantChecker.check(FIVE_MOVES)).doesNotThrowAnyException();
        OmokState won = play(FIVE_MOVES, p(0, 4), p(10, 7), p(0, 6), p(11, 7));
        assertThatCode(() -> OmokInvariantChecker.check(won)).doesNotThrowAnyException();
        OmokState resigned = ENGINE.apply(teams(OmokRule.FREESTYLE), 1, new OmokAction.Resign()).newState();
        assertThatCode(() -> OmokInvariantChecker.check(resigned)).doesNotThrowAnyException();
    }

    @Test
    void timeouts_must_match_seats() {
        violates(with(FIVE_MOVES, FIVE_MOVES.moves(), 1, List.of(0, 0, 0), null), "timeouts size");
    }

    @Test
    void moves_must_be_on_empty_points() {
        List<Move> m = new ArrayList<>(FIVE_MOVES.moves());
        m.set(2, new Move(0, 7, 7, false));
        violates(with(FIVE_MOVES, m, 1, null, null), "occupied");
    }

    @Test
    void seats_must_follow_the_order() {
        List<Move> m = new ArrayList<>(FIVE_MOVES.moves());
        m.set(1, new Move(0, 0, 0, false));
        violates(with(FIVE_MOVES, m, 1, null, null), "seat");
    }

    @Test
    void the_first_seat_must_be_on_the_black_team() {
        // 좌석 순서·색은 첫 수 좌석과 흑 팀에서 파생하므로, 둘이 어긋나는 상태가 색 불일치의 유일한 출처다.
        violates(new OmokState(OmokMode.SOLO, OmokRule.RENJU, 1, 0, FIVE_MOVES.moves(), 1, null, null),
                "first seat not on black team");
    }

    @Test
    void black_must_not_sit_on_a_forbidden_point() {
        // 흑 (6,5)(6,6)(4,7)(5,7) 뒤 (6,7) 은 33 — 수순 끝에 그 수를 둔다.
        OmokState s = play(solo(OmokRule.RENJU), p(6, 5), p(0, 0), p(6, 6), p(0, 2), p(4, 7), p(0, 4), p(5, 7), p(0, 6));
        List<Move> m = new ArrayList<>(s.moves());
        m.add(new Move(0, 6, 7, false));
        violates(with(s, m, 1, null, null), "forbidden");
    }

    @Test
    void a_five_must_end_the_match() {
        OmokState s = play(FIVE_MOVES, p(0, 4), p(10, 7), p(0, 6));
        List<Move> m = new ArrayList<>(s.moves());
        m.add(new Move(0, 11, 7, false));
        m.add(new Move(1, 0, 8, false));
        violates(with(s, m, 0, null, null), "won but the match went on");
    }

    @Test
    void the_turn_seat_must_follow_the_moves() {
        violates(with(FIVE_MOVES, FIVE_MOVES.moves(), 0, null, null), "turn seat");
    }

    @Test
    void an_ended_match_has_no_turn() {
        OmokState resigned = ENGINE.apply(FIVE_MOVES, 0, new OmokAction.Resign()).newState();
        violates(with(resigned, resigned.moves(), 1, null, resigned.result()), "ended match has turn seat");
    }

    @Test
    void the_win_line_must_match_the_board() {
        OmokState won = play(FIVE_MOVES, p(0, 4), p(10, 7), p(0, 6), p(11, 7));
        MatchResult bad = new MatchResult(Stone.BLACK, List.of(0), EndReason.FIVE, -1,
                List.of(p(6, 7), p(7, 7), p(8, 7), p(9, 7), p(10, 7)));
        violates(with(won, won.moves(), -1, null, bad), "win line");
    }

    @Test
    void the_cause_seat_must_be_on_the_losing_side() {
        MatchResult bad = new MatchResult(Stone.WHITE, List.of(1), EndReason.RESIGN, 1, List.of());
        violates(with(FIVE_MOVES, FIVE_MOVES.moves(), -1, null, bad), "cause seat");
    }

    @Test
    void winners_must_be_the_winning_team() {
        MatchResult bad = new MatchResult(Stone.WHITE, List.of(0), EndReason.RESIGN, 0, List.of());
        violates(with(FIVE_MOVES, FIVE_MOVES.moves(), -1, null, bad), "winner seats");
    }

    @Test
    void a_draw_reason_needs_a_draw_and_a_full_board() {
        MatchResult notFull = new MatchResult(null, List.of(), EndReason.BOARD_FULL, -1, List.of());
        violates(with(FIVE_MOVES, FIVE_MOVES.moves(), -1, null, notFull), "BOARD_FULL");
        MatchResult drawWithWinner = new MatchResult(Stone.BLACK, List.of(0), EndReason.BOARD_FULL, -1, List.of());
        violates(with(FIVE_MOVES, FIVE_MOVES.moves(), -1, null, drawWithWinner), "draw flag");
    }

    @Test
    void a_point_off_the_board_is_caught() {
        List<Move> m = new ArrayList<>(FIVE_MOVES.moves());
        m.add(new Move(1, 15, 0, false));
        violates(with(FIVE_MOVES, m, 0, null, null), "off board");
    }

    @Test
    void timeout_counts_stay_small() {
        violates(with(FIVE_MOVES, FIVE_MOVES.moves(), 1, List.of(0, 2), null), "timeout count");
    }
}
