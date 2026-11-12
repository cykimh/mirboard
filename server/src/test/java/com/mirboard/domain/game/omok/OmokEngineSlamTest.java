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

/** 강하게 두기 (룰 §4.1, D-193·D-195) — 연출 전용, 횟수 제한 없음, 룰·상태는 보통 착수와 같다. */
class OmokEngineSlamTest {

    private static OmokEngine.Result slam(OmokState s, int x, int y) {
        return ENGINE.apply(s, s.turnSeat(), new OmokAction.Place(x, y, true));
    }

    @Test
    void a_slam_places_a_normal_stone_and_only_marks_the_event() {
        OmokState s = solo(OmokRule.RENJU);
        OmokEngine.Result r = slam(s, 9, 9);
        assertThat(r.events().getFirst()).isEqualTo(
                new OmokEvent.StonePlaced(0, s.colorOf(0), 9, 9, 1, false, true));
        // 룰 영향 없음 — 같은 수를 보통으로 둔 판과 상태가 같다.
        OmokState plain = ENGINE.apply(s, 0, new OmokAction.Place(9, 9)).newState();
        assertThat(r.newState()).isEqualTo(plain);
        OmokInvariantChecker.check(r.newState());
    }

    @Test
    void a_plain_move_says_slam_false() {
        OmokEngine.Result r = ENGINE.apply(solo(OmokRule.RENJU), 0, new OmokAction.Place(9, 9));
        assertThat(r.events().getFirst()).isEqualTo(
                new OmokEvent.StonePlaced(0, r.newState().colorOf(0), 9, 9, 1, false, false));
    }

    @Test
    void the_same_seat_can_slam_again_and_again() {
        OmokState s = solo(OmokRule.RENJU);
        for (int i = 0; i < 4; i++) {                                   // 흑·백 모두 매 수 강하게 — 거절 없음 (D-195)
            OmokEngine.Result r = slam(s, i * 2, 0);
            assertThat(r.events().getFirst()).isInstanceOfSatisfying(OmokEvent.StonePlaced.class,
                    e -> assertThat(e.slam()).isTrue());
            s = r.newState();
            s = slam(s, i * 2, 4).newState();
        }
        assertThat(s.moves()).hasSize(8);
        OmokInvariantChecker.check(s);
    }

    @Test
    void every_seat_of_a_team_match_can_slam() {
        OmokState s = teams(OmokRule.RENJU);                          // 차례 2,3,0,1
        s = slam(s, 9, 9).newState();
        s = slam(s, 0, 0).newState();
        s = slam(s, 10, 10).newState();
        s = slam(s, 0, 2).newState();
        s = slam(s, 11, 11).newState();
        assertThat(s.moves()).hasSize(5);
        OmokInvariantChecker.check(s);
    }

    @Test
    void a_timeout_move_never_slams() {
        OmokState s = solo(OmokRule.RENJU);
        OmokEngine.Result r = ENGINE.apply(s, 0, ENGINE.timeoutAction(s, 0));
        assertThat(r.events().getFirst()).isInstanceOfSatisfying(OmokEvent.StonePlaced.class,
                e -> assertThat(e.slam()).isFalse());
    }

    @Test
    void a_winning_slam_ends_the_match() {
        OmokState s = play(solo(OmokRule.RENJU), p(3, 7), p(0, 0), p(4, 7), p(0, 2), p(5, 7), p(0, 4), p(6, 7),
                p(0, 6));
        OmokEngine.Result r = slam(s, 7, 7);
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.FIVE);
        assertThat(r.events().getFirst()).isInstanceOfSatisfying(OmokEvent.StonePlaced.class,
                e -> assertThat(e.slam()).isTrue());
        OmokInvariantChecker.check(r.newState());
    }

    @Test
    void a_slam_follows_the_normal_rejections() {
        OmokState s = solo(OmokRule.RENJU);
        assertThatThrownBy(() -> ENGINE.apply(s, 1, new OmokAction.Place(9, 9, true)))
                .isInstanceOfSatisfying(OmokActionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.NOT_YOUR_TURN));
        OmokState placed = slam(s, 9, 9).newState();
        assertThatThrownBy(() -> slam(placed, 9, 9))
                .isInstanceOfSatisfying(OmokActionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.OCCUPIED));
    }
}
