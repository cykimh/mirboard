package com.mirboard.domain.game.dambaduk;

import static com.mirboard.domain.game.dambaduk.DambadukBoards.c;
import static com.mirboard.domain.game.dambaduk.DambadukBoards.playing;
import static com.mirboard.domain.game.dambaduk.DambadukStates.ENGINE;
import static com.mirboard.domain.game.dambaduk.DambadukStates.move;
import static com.mirboard.domain.game.dambaduk.board.Dir.RIGHT;
import static com.mirboard.domain.game.dambaduk.board.Dir.UP;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.invariant.DambadukInvariantChecker;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.Exit;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import org.junit.jupiter.api.Test;

/** 시간 초과 (룰 §8) — 자동 배치·자동 옮김(넘기 아끼기)·연속 2번째 빠짐·손으로 두면 리셋. */
class DambadukEngineTimeoutTest {

    private static DambadukEngine.Result timeout(DambadukState s) {
        return ENGINE.apply(s, s.turnSeat(), ENGINE.timeoutAction(s, s.turnSeat()));
    }

    /** D3 곧은돌(좌석 0)이 네 면 담에 갇혔다. {@code d7Boxed} 면 D7 꺾은돌(좌석 0)도 갇힌다. */
    private static DambadukState boxedD3(boolean d7Boxed) {
        String sep67 = d7Boxed ? "+   +   +   +---+   +   +   +" : "+   +   +   +   +   +   +   +";
        return playing(3, 0,
                "+---+---+---+---+---+---+---+",
                "| .   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   . |",
                "+   +   +   +---+   +   +   +",
                "| B   .   . | A | .   .   b |",
                "+   +   +   +---+   +   +   +",
                "| .   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| C   .   .   .   .   .   c |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | . | .   .   . |",
                sep67,
                "| .   .   . | a | .   .   . |",
                "+---+---+---+---+---+---+---+");
    }

    @Test
    void a_timed_out_placement_goes_to_the_nearest_empty_cell_to_d4() {
        DambadukState s = DambadukStates.two();
        DambadukEngine.Result r = timeout(s);
        assertThat(r.events().getFirst()).isEqualTo(new DambadukEvent.StonePlaced(0, 4, StoneKind.STRAIGHT, 3, 3, true));
        s = r.newState();
        assertThat(s.timeouts()).containsExactly(1, 0);
        s = timeout(s).newState();                                    // 좌석 1 — D4 가 찼으니 거리 1 중 y 가 작은 D3
        assertThat(s.stoneAt(c("D3"))).isPresent();
        s = timeout(s).newState();                                    // 좌석 1 연속 2번째 → 빠짐, 2인이라 판 끝
        assertThat(s.isOver()).isTrue();
        assertThat(s.result().reason()).isEqualTo(EndReason.TIMEOUT);
        assertThat(s.result().causeSeat()).isEqualTo(1);
        assertThat(s.result().winners()).containsExactly(0);
        assertThat(s.exits()).containsExactly(new Exit(1, ExitReason.TIMEOUT));
        DambadukInvariantChecker.check(s);
    }

    @Test
    void three_players_time_out_into_straight_first_then_bent() {
        DambadukState s = DambadukStates.three();
        s = ENGINE.apply(s, 0, new DambadukAction.Place(StoneKind.STRAIGHT, 0, 0)).newState();
        s = DambadukStates.place(s, "A2", "A3");                      // 좌석 1 곧은, 좌석 2 곧은
        DambadukEngine.Result r = timeout(s);                         // 좌석 2 — 남은 것은 꺾은돌뿐
        assertThat(r.events().getFirst()).isEqualTo(new DambadukEvent.StonePlaced(2, 3, StoneKind.BENT, 3, 3, true));
    }

    @Test
    void a_timed_out_move_is_the_first_sorted_option() {
        DambadukState s = DambadukStates.threePlaying();              // 좌석 0: 돌 0 B2 곧은 · 돌 5 F6 꺾은
        // 돌 0 → 경로 [UP] → B1, 담 면은 위(바깥담) 빼고 RIGHT·DOWN·LEFT 중 첫 RIGHT
        assertThat(ENGINE.timeoutMove(s, 0)).isEqualTo(move(0, RIGHT, UP));
        DambadukEngine.Result r = timeout(s);
        assertThat(r.events().getFirst()).isInstanceOfSatisfying(DambadukEvent.StoneMoved.class, m -> {
            assertThat(m.timedOut()).isTrue();
            assertThat(m.stoneId()).isZero();
            assertThat(m.jumpedFace()).isEqualTo(-1);
        });
        assertThat(r.newState().timeouts()).containsExactly(1, 0, 0);
        DambadukInvariantChecker.check(r.newState());
    }

    @Test
    void the_jump_is_saved_while_another_move_exists() {
        DambadukState s = boxedD3(false);
        DambadukAction.Move m = ENGINE.timeoutMove(s, 0);              // D3(돌 1)은 넘기로만 — D7(돌 5) 꺾은돌을 옮긴다
        assertThat(m.jump()).isFalse();
        assertThat(s.stone(m.stoneId()).orElseThrow().cell().label()).isEqualTo("D7");
    }

    @Test
    void the_jump_is_used_when_nothing_else_moves() {
        DambadukState s = boxedD3(true);
        DambadukInvariantChecker.check(s);
        DambadukAction.Move m = ENGINE.timeoutMove(s, 0);
        assertThat(m.jump()).isTrue();
        assertThat(s.stone(m.stoneId()).orElseThrow().cell().label()).isEqualTo("D3");   // 돌 번호 순 — D3 가 먼저
        DambadukState n = timeout(s).newState();
        assertThat(n.jumpsLeft()).containsExactly(0, 1, 1);
        assertThat(n.stone(m.stoneId()).orElseThrow().cell().label()).isEqualTo("D2");
        DambadukInvariantChecker.check(n);
    }

    @Test
    void acting_by_hand_resets_the_count_and_the_second_timeout_in_three_players_removes_the_seat() {
        DambadukState s = DambadukStates.threePlaying();
        s = timeout(s).newState();                                     // 좌석 0 1번째
        s = ENGINE.apply(s, 1, ENGINE.timeoutMove(s, 1)).newState();   // 좌석 1 손으로(같은 수지만 TimeoutAct 아님)
        assertThat(s.timeouts()).containsExactly(1, 0, 0);
        s = ENGINE.apply(s, 2, ENGINE.timeoutMove(s, 2)).newState();
        s = ENGINE.apply(s, 0, ENGINE.timeoutMove(s, 0)).newState();   // 좌석 0 손으로 → 0
        assertThat(s.timeouts()).containsExactly(0, 0, 0);
        s = timeout(s).newState();                                     // 좌석 1 1번째
        s = ENGINE.apply(s, 2, ENGINE.timeoutMove(s, 2)).newState();
        s = ENGINE.apply(s, 0, ENGINE.timeoutMove(s, 0)).newState();
        assertThat(s.turnSeat()).isEqualTo(1);
        DambadukEngine.Result r = timeout(s);                          // 좌석 1 연속 2번째 → 빠짐, 3인이라 계속
        DambadukState n = r.newState();
        assertThat(n.isOver()).isFalse();
        assertThat(n.exits()).containsExactly(new Exit(1, ExitReason.TIMEOUT));
        assertThat(n.stonesOf(1)).isEmpty();
        assertThat(r.events().getFirst()).isEqualTo(
                new DambadukEvent.SeatOut(1, ExitReason.TIMEOUT, java.util.List.of(1, 4)));
        assertThat(r.events().get(1)).isInstanceOf(DambadukEvent.TurnChanged.class);
        assertThat(n.turnSeat()).isEqualTo(2);
        DambadukInvariantChecker.check(n);
    }
}
