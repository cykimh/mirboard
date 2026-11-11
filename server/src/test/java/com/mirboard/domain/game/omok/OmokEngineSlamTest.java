package com.mirboard.domain.game.omok;

import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static com.mirboard.domain.game.omok.OmokStates.solo;
import static com.mirboard.domain.game.omok.OmokStates.teams;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.action.OmokActionRejectedException;
import com.mirboard.domain.game.omok.action.RejectionReason;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.invariant.OmokInvariantChecker;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.OmokState;
import org.junit.jupiter.api.Test;

/** 강하게 두기 (룰 §4.1, D-193) — 연출 전용, 좌석마다 한 판에 한 번, 두 번째는 착수 전체 거절. */
class OmokEngineSlamTest {

    private static OmokEngine.Result slam(OmokState s, int x, int y) {
        return ENGINE.apply(s, s.turnSeat(), new OmokAction.Place(x, y, true));
    }

    @Test
    void a_slam_places_a_normal_stone_and_marks_the_event_and_the_view() {
        OmokState s = solo(OmokRule.RENJU);
        OmokEngine.Result r = slam(s, 7, 7);
        assertThat(r.events().getFirst()).isEqualTo(
                new OmokEvent.StonePlaced(0, s.colorOf(0), 7, 7, 1, false, true));
        OmokState after = r.newState();
        assertThat(after.slams()).containsExactly(1, 0);
        assertThat(after.slamsLeft(0)).isZero();
        assertThat(after.slamsLeft(1)).isEqualTo(1);
        assertThat(ENGINE.publicView(after).slamsLeft()).containsExactly(0, 1);
        // 룰 영향 없음 — 같은 수를 보통으로 둔 판과 수순·차례가 같다.
        OmokState plain = ENGINE.apply(s, 0, new OmokAction.Place(7, 7)).newState();
        assertThat(after.moves()).isEqualTo(plain.moves());
        assertThat(after.turnSeat()).isEqualTo(plain.turnSeat());
        OmokInvariantChecker.check(after);
    }

    @Test
    void a_plain_move_says_slam_false_and_uses_nothing() {
        OmokEngine.Result r = ENGINE.apply(solo(OmokRule.RENJU), 0, new OmokAction.Place(7, 7));
        assertThat(r.events().getFirst()).isEqualTo(
                new OmokEvent.StonePlaced(0, r.newState().colorOf(0), 7, 7, 1, false, false));
        assertThat(r.newState().slams()).containsExactly(0, 0);
    }

    @Test
    void the_second_slam_rejects_the_whole_move_and_leaves_the_state_alone() {
        OmokState s = play(slam(solo(OmokRule.RENJU), 7, 7).newState(), p(0, 0));   // 흑 강하게, 백 보통 → 흑 차례
        OmokState before = s;
        assertThatThrownBy(() -> slam(before, 8, 8))
                .isInstanceOfSatisfying(OmokActionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.SLAM_USED));
        assertThat(before.moves()).hasSize(2);
        // 보통 착수는 그대로 된다.
        OmokState next = ENGINE.apply(before, 0, new OmokAction.Place(8, 8)).newState();
        assertThat(next.moves()).hasSize(3);
        assertThat(next.slams()).containsExactly(1, 0);
    }

    @Test
    void each_seat_has_its_own_slam_even_on_the_same_team() {
        OmokState s = teams(OmokRule.RENJU);                          // 차례 2,3,0,1
        s = slam(s, 7, 7).newState();
        s = slam(s, 0, 0).newState();
        s = slam(s, 8, 8).newState();
        s = slam(s, 0, 2).newState();
        assertThat(s.slams()).containsExactly(1, 1, 1, 1);
        assertThat(ENGINE.publicView(s).slamsLeft()).containsExactly(0, 0, 0, 0);
        OmokState full = s;
        assertThatThrownBy(() -> slam(full, 9, 9)).isInstanceOf(OmokActionRejectedException.class);
        OmokInvariantChecker.check(s);
    }

    @Test
    void a_timeout_move_never_slams() {
        OmokState s = solo(OmokRule.RENJU);
        OmokEngine.Result r = ENGINE.apply(s, 0, ENGINE.timeoutAction(s, 0));
        assertThat(r.events().getFirst()).isInstanceOfSatisfying(OmokEvent.StonePlaced.class,
                e -> assertThat(e.slam()).isFalse());
        assertThat(r.newState().slams()).containsExactly(0, 0);
    }

    @Test
    void a_winning_slam_ends_the_match_with_the_slam_counted() {
        OmokState s = play(solo(OmokRule.RENJU), p(3, 7), p(0, 0), p(4, 7), p(0, 2), p(5, 7), p(0, 4), p(6, 7),
                p(0, 6));
        OmokEngine.Result r = slam(s, 7, 7);
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.FIVE);
        assertThat(r.newState().slams()).containsExactly(1, 0);
        assertThat(r.events().getFirst()).isInstanceOfSatisfying(OmokEvent.StonePlaced.class,
                e -> assertThat(e.slam()).isTrue());
        OmokInvariantChecker.check(r.newState());
    }

    @Test
    void a_slam_out_of_turn_is_still_not_your_turn() {
        OmokState s = solo(OmokRule.RENJU);
        assertThatThrownBy(() -> ENGINE.apply(s, 1, new OmokAction.Place(7, 7, true)))
                .isInstanceOfSatisfying(OmokActionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.NOT_YOUR_TURN));
    }

    @Test
    void the_invariant_rejects_a_second_slam_in_the_state() {
        OmokState s = solo(OmokRule.RENJU);
        OmokState bad = new OmokState(s.mode(), s.rule(), s.blackTeam(), s.firstSeat(), s.moves(), s.turnSeat(),
                s.timeouts(), null, java.util.List.of(2, 0));
        assertThatThrownBy(() -> OmokInvariantChecker.check(bad)).hasMessageContaining("slam count");
    }
}
