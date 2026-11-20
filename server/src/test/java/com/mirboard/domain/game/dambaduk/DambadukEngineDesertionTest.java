package com.mirboard.domain.game.dambaduk;

import static com.mirboard.domain.game.dambaduk.DambadukStates.ENGINE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.DambadukEngine.Desertion.Outcome;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.invariant.DambadukInvariantChecker;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.Exit;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Standing;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 탈주 (룰 §9) — 2인 판 끝(돌은 그대로), 3인 돌 치우고 계속, 배치 중 탈주, 1명만 남으면 끝, 해당 없음. */
class DambadukEngineDesertionTest {

    @Test
    void two_players_desertion_ends_the_match_and_keeps_the_board() {
        DambadukState s = DambadukStates.twoPlaying();
        DambadukEngine.Desertion d = ENGINE.desert(s, 0);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        DambadukState n = d.newState();
        assertThat(n.result().reason()).isEqualTo(EndReason.DESERTED);
        assertThat(n.result().causeSeat()).isZero();
        assertThat(n.result().winners()).containsExactly(1);
        assertThat(n.result().standings()).extracting(Standing::rank).containsExactly(2, 1);
        assertThat(n.result().standings().getFirst().exit()).isEqualTo(ExitReason.DESERTED);
        assertThat(n.stones()).hasSize(8);                                   // 2인은 돌을 치우지 않는다
        assertThat(d.events()).extracting(e -> e.getClass().getSimpleName()).containsExactly("SeatOut", "MatchEnded");
        DambadukInvariantChecker.check(n);
    }

    @Test
    void three_players_desertion_off_turn_removes_the_stones_and_keeps_the_turn() {
        DambadukState s = DambadukStates.threePlaying();                     // 좌석 0 차례
        DambadukEngine.Desertion d = ENGINE.desert(s, 2);
        assertThat(d.outcome()).isEqualTo(Outcome.CONTINUED);
        DambadukState n = d.newState();
        assertThat(n.stonesOf(2)).isEmpty();
        assertThat(n.exits()).containsExactly(new Exit(2, ExitReason.DESERTED));
        assertThat(n.turnSeat()).isZero();
        assertThat(d.events().getFirst()).isEqualTo(new DambadukEvent.SeatOut(2, ExitReason.DESERTED, List.of(2, 3)));
        assertThat(d.events().get(1)).isInstanceOfSatisfying(DambadukEvent.TurnChanged.class,
                tc -> assertThat(tc.seat()).isZero());
        assertThat(n.jumpsLeft()).containsExactly(1, 1, 1);                  // 3인으로 시작한 판 — 남은 둘은 계속 넘기를 쓴다
        DambadukInvariantChecker.check(n);
        DambadukEngine.Desertion again = ENGINE.desert(n, 2);
        assertThat(again.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(again.newState()).isSameAs(n);
        assertThat(again.events()).isEmpty();
    }

    @Test
    void the_second_desertion_leaves_one_player_who_wins() {
        DambadukState s = DambadukStates.threePlaying();
        s = ENGINE.desert(s, 0).newState();                                  // 차례였던 좌석 0 → 좌석 1 로
        assertThat(s.turnSeat()).isEqualTo(1);
        DambadukEngine.Desertion d = ENGINE.desert(s, 1);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        DambadukState n = d.newState();
        assertThat(n.result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(n.result().winners()).containsExactly(2);
        assertThat(n.result().draw()).isFalse();
        assertThat(n.result().standings()).extracting(Standing::rank).containsExactly(2, 2, 1);
        DambadukInvariantChecker.check(n);
    }

    @Test
    void a_desertion_while_placing_drops_the_seat_from_the_snake_order() {
        DambadukState s = DambadukStates.place(DambadukStates.three(), "A1", "B1");   // 좌석 0, 1 놓음 — 남은 순서 2,2,1,0
        DambadukEngine.Desertion d = ENGINE.desert(s, 2);
        DambadukState n = d.newState();
        assertThat(n.placementQueue()).containsExactly(1, 0);
        assertThat(n.turnSeat()).isEqualTo(1);
        assertThat(n.phase()).isEqualTo(Phase.PLACING);
        DambadukInvariantChecker.check(n);
        n = DambadukStates.place(n, "C1", "D1");                              // 끝 → 선(좌석 0)부터 옮김
        assertThat(n.phase()).isEqualTo(Phase.PLAYING);
        assertThat(n.turnSeat()).isZero();
        DambadukInvariantChecker.check(n);
    }

    @Test
    void the_first_seat_deserting_before_play_hands_the_first_move_to_the_next_seat() {
        DambadukState s = DambadukStates.place(DambadukStates.three(), "A1", "B1", "C1", "D1", "E1");  // 마지막 좌석 0 차례
        DambadukState n = ENGINE.desert(s, 0).newState();
        assertThat(n.phase()).isEqualTo(Phase.PLAYING);
        assertThat(n.turnSeat()).isEqualTo(1);
        DambadukInvariantChecker.check(n);
    }

    @Test
    void desertion_of_an_ended_match_or_a_bad_seat_is_not_applicable() {
        DambadukState ended = ENGINE.desert(DambadukStates.twoPlaying(), 1).newState();
        assertThat(ENGINE.desert(ended, 0).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(ENGINE.desert(DambadukStates.twoPlaying(), 7).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }
}
