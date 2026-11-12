package com.mirboard.domain.game.omok;

import static com.mirboard.domain.game.omok.OmokBoards.diagram;
import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.blackToMove;
import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static com.mirboard.domain.game.omok.OmokStates.solo;
import static com.mirboard.domain.game.omok.OmokStates.teams;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.OmokBoards.Diagram;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 시간 초과 (룰 §8) — 자동 수 자리, 금수 건너뛰기, 연속 수 리셋, 2연속 패, 2:2 사람별 계수. */
class OmokEngineTimeoutTest {

    private static OmokEngine.Result timeout(OmokState s) {
        return ENGINE.apply(s, s.turnSeat(), ENGINE.timeoutAction(s, s.turnSeat()));
    }

    @Test
    void timeout_action_is_the_server_only_timeout_place() {
        assertThat(ENGINE.timeoutAction(solo(OmokRule.RENJU), 0)).isInstanceOf(OmokAction.TimeoutPlace.class);
    }

    @Test
    void first_move_timeout_goes_to_the_center() {
        OmokEngine.Result r = timeout(solo(OmokRule.RENJU));
        assertThat(r.events().getFirst()).isEqualTo(new OmokEvent.StonePlaced(0, Stone.BLACK, 9, 9, 1, true));
        assertThat(r.newState().timeouts()).containsExactly(1, 0);
        assertThat(r.newState().moves().getFirst().timedOut()).isTrue();
    }

    @Test
    void the_auto_move_is_the_nearest_legal_point_to_the_last_move_breaking_ties_by_y_then_x() {
        OmokState s = play(solo(OmokRule.RENJU), p(7, 7));
        assertThat(ENGINE.timeoutPoint(s)).isEqualTo(p(6, 6));        // 거리 1 의 8칸 중 y 가 작고 x 가 작은 쪽
        s = play(s, p(6, 6), p(0, 14));
        // 직전 수 (0,14) — 거리 1 은 (0,13)·(1,13)·(1,14), y 가 작은 (0,13).
        assertThat(ENGINE.timeoutPoint(s)).isEqualTo(p(0, 13));
    }

    @Test
    void black_auto_move_skips_a_forbidden_point() {
        // 33 자리 (7,7) 의 대각 옆 (8,8) 에 백의 직전 수 — 거리 1 후보를 y·x 순으로 보면 (7,7) 이 맨 앞이다.
        Diagram d = diagram(
                ".........",
                ".........",
                "....X....",
                "....X....",
                "..XX*....",
                ".....O...",
                ".........");
        OmokState s = blackToMove(OmokRule.RENJU, d.board());
        s = withLastWhite(s, p(8, 8));
        assertThat(ForbiddenRule.check(s.board(), p(7, 7), OmokRule.RENJU)).isPresent();
        Point chosen = ENGINE.timeoutPoint(s);
        assertThat(chosen).isNotEqualTo(p(7, 7));
        assertThat(chosen).isEqualTo(p(8, 7));                        // (7,7) 금수를 건너뛴 다음 후보
    }

    /** 마지막 백 수가 target 이 되도록 백 수 하나와 자리를 맞바꾼다. */
    private static OmokState withLastWhite(OmokState s, Point target) {
        var moves = new java.util.ArrayList<>(s.moves());
        int idx = -1;
        for (int i = 0; i < moves.size(); i++) {
            if (moves.get(i).point().equals(target)) {
                idx = i;
            }
        }
        var last = moves.getLast();
        var found = moves.get(idx);
        moves.set(idx, new com.mirboard.domain.game.omok.state.Move(found.seat(), last.x(), last.y(), false));
        moves.set(moves.size() - 1, new com.mirboard.domain.game.omok.state.Move(last.seat(), target.x(), target.y(), false));
        return new OmokState(s.mode(), s.rule(), s.blackTeam(), s.firstSeat(), moves, s.turnSeat(), null, null);
    }

    @Test
    void the_second_consecutive_timeout_loses_without_placing() {
        OmokState s = timeout(solo(OmokRule.RENJU)).newState();      // 흑 시간 초과 1
        s = play(s, p(0, 0));                                          // 백 정상
        OmokEngine.Result r = timeout(s);                              // 흑 연속 2번째
        assertThat(r.events()).containsExactly(new OmokEvent.MatchEnded(Stone.WHITE, List.of(1), EndReason.TIMEOUT, 0,
                List.of(), 2));
        assertThat(r.newState().moves()).hasSize(2);
    }

    @Test
    void a_manual_move_resets_the_count() {
        OmokState s = timeout(solo(OmokRule.RENJU)).newState();
        s = play(s, p(0, 0), p(1, 0), p(0, 1));                        // 흑이 손으로 한 수 → 0
        assertThat(s.timeouts()).containsExactly(0, 0);
        OmokEngine.Result r = timeout(s);
        assertThat(r.newState().result()).isNull();
        assertThat(r.newState().timeouts()).containsExactly(1, 0);
    }

    @Test
    void team_counts_are_per_person_and_a_second_strike_loses_for_the_team() {
        OmokState s = teams(OmokRule.RENJU);                           // 차례 2,3,0,1
        s = timeout(s).newState();                                     // 좌석 2 시간 초과
        s = play(s, p(0, 0));                                          // 3
        s = timeout(s).newState();                                     // 좌석 0 시간 초과 — 팀원과 합치지 않는다
        assertThat(s.result()).isNull();
        assertThat(s.timeouts()).containsExactly(1, 0, 1, 0);
        s = play(s, p(0, 2));                                          // 1
        OmokEngine.Result r = timeout(s);                              // 좌석 2 연속 2번째 → 흑 팀 패
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.TIMEOUT);
        assertThat(r.newState().result().causeSeat()).isEqualTo(2);
        assertThat(r.newState().result().winnerSeats()).containsExactly(1, 3);
    }

    @Test
    void an_auto_move_that_makes_five_wins() {
        // 흑 (6..9, 7) 넷, 백의 직전 수 (10,8) — 거리 1 후보 중 y·x 가 가장 앞선 빈칸이 (10,7) 이라 자동 수가 5목이다.
        OmokState s = play(solo(OmokRule.RENJU), p(6, 7), p(0, 0), p(7, 7), p(0, 2), p(8, 7), p(0, 4), p(9, 7),
                p(10, 8));
        OmokEngine.Result r = timeout(s);
        assertThat(r.events().getFirst()).isEqualTo(new OmokEvent.StonePlaced(0, Stone.BLACK, 10, 7, 9, true));
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.FIVE);
        assertThat(r.newState().result().winnerSeats()).containsExactly(0);
    }
}
