package com.mirboard.domain.game.dambaduk.invariant;

import static com.mirboard.domain.game.dambaduk.DambadukBoards.playing;
import static com.mirboard.domain.game.dambaduk.DambadukStates.ENGINE;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.dambaduk.DambadukStates;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.Exit;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.dambaduk.state.MatchResult;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Standing;
import com.mirboard.domain.game.dambaduk.state.Stone;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 불변식 검사기가 정말 잡는지 — 통과하는 상태 몇 개와 고의 위반. */
class DambadukInvariantCheckerTest {

    /** 상태 레코드를 고쳐 다시 만드는 보조. */
    private static final class B {
        int seatCount;
        int firstSeat;
        Phase phase;
        List<Stone> stones;
        List<Integer> walls;
        List<Integer> queue;
        int turn;
        List<Integer> jumps;
        List<Integer> timeouts;
        List<Exit> exits;
        int next;
        int moves;
        int jumpCount;
        MatchResult result;

        B(DambadukState s) {
            seatCount = s.seatCount();
            firstSeat = s.firstSeat();
            phase = s.phase();
            stones = new ArrayList<>(s.stones());
            walls = new ArrayList<>(s.walls());
            queue = new ArrayList<>(s.placementQueue());
            turn = s.turnSeat();
            jumps = new ArrayList<>(s.jumpsLeft());
            timeouts = new ArrayList<>(s.timeouts());
            exits = new ArrayList<>(s.exits());
            next = s.nextStoneId();
            moves = s.moveCount();
            jumpCount = s.jumpCount();
            result = s.result();
        }

        DambadukState build() {
            return new DambadukState(seatCount, firstSeat, phase, stones, walls, queue, turn, jumps, timeouts, exits,
                    next, moves, jumpCount, result);
        }
    }

    private static void violates(DambadukState s, String fragment) {
        assertThatThrownBy(() -> DambadukInvariantChecker.check(s)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fragment);
    }

    /** 12.3 의 끝난 판 — 다툼 터 없음. */
    private static DambadukState separatedBoard() {
        return playing(2, 0,
                "+---+---+---+---+---+---+---+",
                "| .   a   . | .   .   b   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | B   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| A   .   . | .   .   .   B |",
                "+   +   +   +   +   +   +   +",
                "| .   .   A | .   b   .   . |",
                "+   +   +   +---+---+---+---+",
                "| .   .   . | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   a   . | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | .   .   .   . |",
                "+---+---+---+---+---+---+---+");
    }

    @Test
    void valid_states_pass() {
        assertThatCode(() -> {
            DambadukInvariantChecker.check(DambadukStates.two());
            DambadukInvariantChecker.check(DambadukStates.twoPlaying());
            DambadukInvariantChecker.check(DambadukStates.threePlaying());
            DambadukInvariantChecker.check(ENGINE.desert(DambadukStates.threePlaying(), 1).newState());
            DambadukInvariantChecker.check(ENGINE.desert(DambadukStates.twoPlaying(), 1).newState());
        }).doesNotThrowAnyException();
    }

    @Test
    void stones_two_on_one_cell_or_ids_past_the_counter() {
        B b = new B(DambadukStates.twoPlaying());
        Stone s = b.stones.get(4);
        b.stones.set(4, new Stone(s.id(), s.seat(), s.kind(), 1, 1));                   // B2 의 꺾은돌 위
        violates(b.build(), "two stones on B2");
        b = new B(DambadukStates.twoPlaying());
        b.next = 7;
        violates(b.build(), "≥ next");
    }

    @Test
    void a_seat_with_the_wrong_number_of_stones() {
        B b = new B(DambadukStates.twoPlaying());
        b.stones.remove(7);
        violates(b.build(), "placements left");
        b = new B(DambadukStates.three());
        b.queue.removeFirst();
        b.turn = b.queue.getFirst();
        violates(b.build(), "placements left");                                       // 순서는 줄었는데 돌이 없다
    }

    @Test
    void walls_must_match_the_moves_and_jumps() {
        B b = new B(DambadukStates.twoPlaying());
        b.walls.add(0);
        violates(b.build(), "walls 1 ≠ moves 0");
        b = new B(DambadukStates.twoPlaying());
        b.jumps.set(0, 1);
        violates(b.build(), "two-player match has a jump");
        b = new B(DambadukStates.threePlaying());
        b.jumpCount = 1;
        b.moves = 1;
        violates(b.build(), "jumps used 0 ≠ jump count 1");
    }

    @Test
    void removed_seats_and_turns() {
        B b = new B(DambadukStates.threePlaying());
        b.exits.add(new Exit(2, ExitReason.DESERTED));
        violates(b.build(), "removed seat still has stone");
        b = new B(ENGINE.desert(DambadukStates.threePlaying(), 2).newState());
        b.turn = 2;
        violates(b.build(), "turn seat 2 is not active");
        b = new B(DambadukStates.twoPlaying());
        b.exits.add(new Exit(1, ExitReason.TIMEOUT));
        violates(b.build(), "goes on after an exit");
        b = new B(DambadukStates.twoPlaying());
        b.timeouts.set(0, 2);
        violates(b.build(), "timeout count 2");
    }

    @Test
    void phases_and_turns() {
        B b = new B(DambadukStates.two());
        b.turn = 1;
        violates(b.build(), "placing turn ≠ queue head");
        b = new B(DambadukStates.two());
        b.phase = Phase.PLAYING;
        violates(b.build(), "playing with placements left");
        violates(separatedBoard(), "playing but nothing is contested");
    }

    @Test
    void a_turn_seat_that_cannot_move_or_a_frozen_board_left_running() {
        DambadukState skip = playing(2, 1,
                "+---+---+---+---+---+---+---+",
                "| b | .   .   .   .   . | B |",
                "+   +   +   +   +   +   +---+",
                "| A   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   a   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   A   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   a   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   . |",
                "+---+   +   +   +   +   +---+",
                "| B | .   .   .   .   . | b |",
                "+---+---+---+---+---+---+---+");
        violates(skip, "turn seat 1 cannot move");
        DambadukState frozen = playing(2, 0,
                "+---+---+---+---+---+---+---+",
                "| a   .   .   .   .   .   a |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   A   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   . |",
                "+---+---+---+---+---+---+---+",
                "| .   .   . | A   B | B   . |",
                "+---+---+---+---+---+---+---+",
                "| .   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| b   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   b |",
                "+---+---+---+---+---+---+---+");
        violates(frozen, "playing but frozen");
    }

    @Test
    void an_ended_match_must_agree_with_its_board() {
        DambadukState ended = ENGINE.desert(DambadukStates.twoPlaying(), 1).newState();
        B b = new B(ended);
        List<Standing> wrong = new ArrayList<>(ended.result().standings());
        Standing first = wrong.getFirst();
        wrong.set(0, new Standing(first.seat(), first.score() + 1, first.largest(), first.rank(), first.exit()));
        b.result = new MatchResult(ended.result().reason(), wrong, ended.result().winners(), false, 1);
        violates(b.build(), "standings");
        b = new B(ended);
        b.result = new MatchResult(ended.result().reason(), ended.result().standings(), List.of(), true, 1);
        violates(b.build(), "draw flag");
        b = new B(ended);
        b.result = new MatchResult(EndReason.SEPARATED, ended.result().standings(), List.of(0), false, -1);
        violates(b.build(), "SEPARATED but contested");
        b = new B(ended);
        b.result = new MatchResult(EndReason.DESERTED, ended.result().standings(), List.of(0), false, 0);
        violates(b.build(), "cause 0");
        b = new B(ended);
        b.turn = 0;
        violates(b.build(), "ended match phase/turn");
    }
}
