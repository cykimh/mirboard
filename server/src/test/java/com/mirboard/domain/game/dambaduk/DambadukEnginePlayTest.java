package com.mirboard.domain.game.dambaduk;

import static com.mirboard.domain.game.dambaduk.DambadukBoards.c;
import static com.mirboard.domain.game.dambaduk.DambadukBoards.playing;
import static com.mirboard.domain.game.dambaduk.DambadukStates.ENGINE;
import static com.mirboard.domain.game.dambaduk.DambadukStates.jump;
import static com.mirboard.domain.game.dambaduk.DambadukStates.move;
import static com.mirboard.domain.game.dambaduk.DambadukStates.threePlaying;
import static com.mirboard.domain.game.dambaduk.DambadukStates.twoPlaying;
import static com.mirboard.domain.game.dambaduk.board.Dir.DOWN;
import static com.mirboard.domain.game.dambaduk.board.Dir.LEFT;
import static com.mirboard.domain.game.dambaduk.board.Dir.RIGHT;
import static com.mirboard.domain.game.dambaduk.board.Dir.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.action.DambadukActionRejectedException;
import com.mirboard.domain.game.dambaduk.action.RejectionReason;
import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.invariant.DambadukInvariantChecker;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.MatchResult;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Standing;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 엔진 동작 (룰 §2~§7) — 시작·배치·거절 사유 전부·옮김과 담·담 넘기·건너뜀·끝 ①②·동점과 무승부.
 *
 * <p>{@link DambadukStates#twoPlaying()} 판(선 = 좌석 0): 흑(0) 0 B2·1 F6 꺾은돌, 4 C4·7 D5 곧은돌 / 백(1) 2 F2·3 B6 꺾은돌,
 * 5 E4·6 D3 곧은돌, 담 없음, 흑 차례.
 */
class DambadukEnginePlayTest {

    private static DambadukEngine.Result apply(DambadukState s, DambadukAction a) {
        return ENGINE.apply(s, s.turnSeat(), a);
    }

    private static RejectionReason rejection(DambadukState s, int seat, DambadukAction a) {
        try {
            ENGINE.apply(s, seat, a);
        } catch (DambadukActionRejectedException e) {
            assertThat(e.code()).isEqualTo(e.reason().name());
            return e.reason();
        }
        throw new AssertionError("not rejected: " + a);
    }

    private static String at(DambadukState s, int stoneId) {
        return s.stone(stoneId).orElseThrow().cell().label();
    }

    @Nested
    class Start {

        @Test
        void two_players_start_with_the_fixed_bent_stones_and_the_first_seat_is_black() {
            DambadukState s = ENGINE.startMatch(2, 1);
            assertThat(s.stones()).extracting(Stone::id, Stone::seat, Stone::kind, st -> st.cell().label())
                    .containsExactly(
                            Tuple.tuple(0, 1, StoneKind.BENT, "B2"), Tuple.tuple(1, 1, StoneKind.BENT, "F6"),
                            Tuple.tuple(2, 0, StoneKind.BENT, "F2"), Tuple.tuple(3, 0, StoneKind.BENT, "B6"));
            assertThat(s.placementQueue()).containsExactly(1, 0, 0, 1);
            assertThat(s.turnSeat()).isEqualTo(1);
            assertThat(s.colorIndex(1)).isZero();                                       // 선 = 흑
            assertThat(s.colorIndex(0)).isEqualTo(1);
            assertThat(s.jumpsLeft()).containsExactly(0, 0);
            assertThat(s.phase()).isEqualTo(Phase.PLACING);
            assertThat(s.nextStoneId()).isEqualTo(4);
            DambadukInvariantChecker.check(s);
        }

        @Test
        void three_players_start_empty_with_one_jump_each_and_a_snake_order() {
            DambadukState s = ENGINE.startMatch(3, 2);
            assertThat(s.stones()).isEmpty();
            assertThat(s.placementQueue()).containsExactly(2, 0, 1, 1, 0, 2);
            assertThat(s.jumpsLeft()).containsExactly(1, 1, 1);
            assertThat(List.of(s.colorIndex(2), s.colorIndex(0), s.colorIndex(1))).containsExactly(0, 1, 2);
            DambadukInvariantChecker.check(s);
        }

        @Test
        void bad_start_arguments_are_refused() {
            assertThatThrownBy(() -> ENGINE.startMatch(4, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ENGINE.startMatch(1, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ENGINE.startMatch(2, 2)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Placing {

        @Test
        void two_players_place_straight_stones_in_snake_order_then_black_moves_first() {
            DambadukState s = DambadukStates.two();
            List<Integer> placers = new java.util.ArrayList<>();
            DambadukEngine.Result last = null;
            for (String label : List.of("C4", "E4", "D3", "D5")) {
                placers.add(s.turnSeat());
                assertThat(ENGINE.placeKinds(s, s.turnSeat())).containsExactly(StoneKind.STRAIGHT);
                Cell cell = c(label);
                last = apply(s, new DambadukAction.Place(StoneKind.STRAIGHT, cell.x(), cell.y()));
                assertThat(last.events().getFirst()).isInstanceOf(DambadukEvent.StonePlaced.class);
                s = last.newState();
                DambadukInvariantChecker.check(s);
            }
            DambadukState done = s;
            assertThat(placers).containsExactly(0, 1, 1, 0);
            assertThat(last.events()).last().isInstanceOfSatisfying(DambadukEvent.TurnChanged.class, tc -> {
                assertThat(tc.seat()).isZero();
                assertThat(tc.phase()).isEqualTo(Phase.PLAYING);
                assertThat(tc.options()).isEqualTo(ENGINE.options(done, 0)).isNotEmpty();
                assertThat(tc.territory()).isEqualTo(ENGINE.territory(done));
            });
            assertThat(s.phase()).isEqualTo(Phase.PLAYING);
            assertThat(s.turnSeat()).isZero();
            assertThat(s.stones()).extracting(Stone::id).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
            assertThat(at(s, 7)).isEqualTo("D5");
        }

        @Test
        void three_players_choose_the_kind_and_cannot_place_a_kind_twice() {
            DambadukState s = DambadukStates.three();
            s = apply(s, new DambadukAction.Place(StoneKind.BENT, 3, 3)).newState();           // 좌석 0 꺾은돌 먼저
            s = DambadukStates.place(s, "A1", "G7", "G1");                                   // 좌석 1, 2, 2
            assertThat(s.turnSeat()).isEqualTo(1);
            assertThat(ENGINE.placeKinds(s, 1)).containsExactly(StoneKind.BENT);
            assertThat(rejection(s, 1, new DambadukAction.Place(StoneKind.STRAIGHT, 0, 6)))
                    .isEqualTo(RejectionReason.NO_STONE_LEFT);
            s = DambadukStates.place(s, "A7");                                               // 좌석 1 꺾은돌
            assertThat(ENGINE.placeKinds(s, 0)).containsExactly(StoneKind.STRAIGHT);
            s = DambadukStates.place(s, "D7");                                               // 좌석 0 곧은돌
            assertThat(s.phase()).isEqualTo(Phase.PLAYING);
            assertThat(s.turnSeat()).isZero();
            DambadukInvariantChecker.check(s);
        }

        @Test
        void placement_rejections() {
            DambadukState s = DambadukStates.two();
            assertThat(rejection(s, 1, new DambadukAction.Place(StoneKind.STRAIGHT, 0, 0)))
                    .isEqualTo(RejectionReason.NOT_YOUR_TURN);
            assertThat(rejection(s, 5, new DambadukAction.Place(StoneKind.STRAIGHT, 0, 0)))
                    .isEqualTo(RejectionReason.NOT_YOUR_TURN);
            assertThat(rejection(s, 0, new DambadukAction.Place(StoneKind.STRAIGHT, 7, 0)))
                    .isEqualTo(RejectionReason.OUT_OF_BOARD);
            assertThat(rejection(s, 0, new DambadukAction.Place(StoneKind.BENT, 9, 9)))
                    .isEqualTo(RejectionReason.OUT_OF_BOARD);                              // 판 밖이 종류보다 먼저
            assertThat(rejection(s, 0, new DambadukAction.Place(StoneKind.BENT, 0, 0)))
                    .isEqualTo(RejectionReason.NO_STONE_LEFT);                             // 2인 꺾은돌은 이미 다 놓였다
            assertThat(rejection(s, 0, new DambadukAction.Place(null, 0, 0)))
                    .isEqualTo(RejectionReason.NO_STONE_LEFT);
            assertThat(rejection(s, 0, new DambadukAction.Place(StoneKind.STRAIGHT, 1, 1)))
                    .isEqualTo(RejectionReason.OCCUPIED);                                 // B2 고정 꺾은돌
            assertThat(rejection(s, 0, move(0, DOWN, RIGHT))).isEqualTo(RejectionReason.WRONG_PHASE);
        }
    }

    @Nested
    class Moving {

        @Test
        void a_move_places_its_wall_and_passes_the_turn() {
            DambadukState s = twoPlaying();
            DambadukEngine.Result r = apply(s, move(4, LEFT, UP, UP));                       // C4 → C2, 왼쪽 면
            DambadukState n = r.newState();
            int face = Grid.face(c("C2"), LEFT);
            assertThat(at(n, 4)).isEqualTo("C2");
            assertThat(n.walls()).containsExactly(face);
            assertThat(n.moveCount()).isEqualTo(1);
            assertThat(n.turnSeat()).isEqualTo(1);
            assertThat(r.events()).hasSize(2);
            assertThat(r.events().getFirst()).isEqualTo(
                    new DambadukEvent.StoneMoved(0, 4, List.of(UP, UP), 2, 3, 2, 1, LEFT, face, -1, false));
            assertThat(r.events().get(1)).isInstanceOfSatisfying(DambadukEvent.TurnChanged.class, tc -> {
                assertThat(tc.seat()).isEqualTo(1);
                assertThat(tc.options()).isEqualTo(ENGINE.options(n, 1));
            });
            DambadukInvariantChecker.check(n);
        }

        @Test
        void the_wall_may_close_the_face_the_stone_came_through() {
            DambadukState n = apply(twoPlaying(), move(4, DOWN, UP)).newState();             // C4 → C3, 아래(C3/C4) 면
            assertThat(n.walls()).containsExactly(Grid.face(c("C3"), DOWN));
            assertThat(rejection(n, 0, move(4, UP, DOWN))).isEqualTo(RejectionReason.NOT_YOUR_TURN);
            DambadukInvariantChecker.check(n);
        }

        @Test
        void rejections_before_looking_at_the_board() {
            DambadukState s = twoPlaying();
            assertThat(rejection(s, 1, move(5, UP, UP))).isEqualTo(RejectionReason.NOT_YOUR_TURN);
            assertThat(rejection(s, 0, new DambadukAction.Place(StoneKind.STRAIGHT, 0, 0)))
                    .isEqualTo(RejectionReason.WRONG_PHASE);
            assertThat(rejection(s, 0, move(5, UP, UP))).isEqualTo(RejectionReason.NOT_YOUR_STONE);          // 남의 돌
            assertThat(rejection(s, 0, move(42, UP, UP))).isEqualTo(RejectionReason.NOT_YOUR_STONE);         // 없는 돌
            assertThat(rejection(s, 0, move(5, UP, UP, LEFT))).isEqualTo(RejectionReason.NOT_YOUR_STONE);    // 돌이 모양보다 먼저
            assertThat(rejection(s, 0, move(4, UP, UP, LEFT))).isEqualTo(RejectionReason.BAD_PATH);          // 곧은돌 꺾기
            assertThat(rejection(s, 0, move(0, UP, UP, UP))).isEqualTo(RejectionReason.BAD_PATH);            // 꺾은돌 곧게 2칸
            assertThat(rejection(s, 0, move(0, UP, UP, DOWN))).isEqualTo(RejectionReason.BAD_PATH);          // 되돌아오기
            assertThat(rejection(s, 0, move(4, UP))).isEqualTo(RejectionReason.BAD_PATH);                   // 빈 경로
            assertThat(rejection(s, 0, move(4, UP, UP, UP, UP))).isEqualTo(RejectionReason.BAD_PATH);        // 3걸음
            assertThat(rejection(s, 0, jump(4, UP, LEFT, RIGHT))).isEqualTo(RejectionReason.BAD_PATH);       // 모양이 넘기보다 먼저
            assertThat(rejection(s, 0, jump(4, UP, UP))).isEqualTo(RejectionReason.JUMP_UNAVAILABLE);       // 2인은 담 넘기 없음
        }

        @Test
        void rejections_on_the_board() {
            DambadukState s = twoPlaying();
            assertThat(rejection(s, 0, move(7, LEFT, UP, UP))).isEqualTo(RejectionReason.PATH_BLOCKED);     // D5 → D4 → D3 돌
            assertThat(rejection(s, 0, move(4, UP, RIGHT, RIGHT))).isEqualTo(RejectionReason.PATH_BLOCKED); // C4 → D4 → E4 돌
            assertThat(rejection(s, 0, move(0, UP, UP))).isEqualTo(RejectionReason.WALL_SIDE_TAKEN);        // B1 위는 바깥담
            assertThat(rejection(s, 0, move(0, null, UP))).isEqualTo(RejectionReason.WALL_SIDE_TAKEN);
            // 흑 C4 → B4, 위(B3/B4)에 담 / 백 E4 → E5, 아래에 담
            DambadukState w = apply(apply(s, move(4, UP, LEFT)).newState(), move(5, DOWN, DOWN)).newState();
            assertThat(rejection(w, 0, move(4, LEFT, UP))).isEqualTo(RejectionReason.WALL_IN_PATH);         // B4 → B3 담
            assertThat(rejection(w, 0, move(4, DOWN, UP))).isEqualTo(RejectionReason.WALL_IN_PATH);         // 지난 담이 면보다 먼저
            assertThat(rejection(w, 0, move(0, DOWN, DOWN))).isEqualTo(RejectionReason.WALL_SIDE_TAKEN);    // B3 아래는 이미 담
            assertThat(rejection(w, 0, move(0, LEFT, DOWN, DOWN))).isEqualTo(RejectionReason.BAD_PATH);
            DambadukState edge = playing(2, 0,
                    "+---+---+---+---+---+---+---+",
                    "| a   .   .   .   .   .   b |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| A   .   .   .   .   .   B |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| a   .   .   .   .   A   b |",
                    "+---+---+---+---+---+---+---+");
            int straightA4 = edge.stoneAt(c("A4")).orElseThrow().id();
            assertThat(rejection(edge, 0, move(straightA4, UP, LEFT))).isEqualTo(RejectionReason.PATH_BLOCKED);  // 바깥담
            int bentA1 = edge.stoneAt(c("A1")).orElseThrow().id();
            assertThat(rejection(edge, 0, move(bentA1, UP, UP, RIGHT))).isEqualTo(RejectionReason.PATH_BLOCKED); // 첫 걸음이 판 밖
        }

        @Test
        void a_jump_removes_the_crossed_wall_and_may_put_it_straight_back() {
            DambadukState s = threePlaying();                         // 0 B2(0 곧은) 1 F2(1 곧은) 2 D4(2 곧은) 3 D6 4 B6 5 F6
            assertThat(rejection(s, 0, jump(0, UP, UP))).isEqualTo(RejectionReason.JUMP_WITHOUT_WALL);    // B2 → B1, 담 없음
            s = apply(s, move(0, DOWN, DOWN)).newState();             // 흑 B2 → B3, 아래(B3/B4)
            s = apply(s, move(1, LEFT, DOWN)).newState();             // 좌석 1 F2 → F3
            s = apply(s, move(2, UP, UP)).newState();                 // 좌석 2 D4 → D3
            int face = Grid.face(c("B3"), DOWN);
            assertThat(rejection(s, 0, move(0, UP, DOWN))).isEqualTo(RejectionReason.WALL_IN_PATH);
            DambadukEngine.Result r = apply(s, jump(0, UP, DOWN));    // B3 → B4 넘어가며 그 담을 없애고 같은 면에 다시
            DambadukState n = r.newState();
            assertThat(at(n, 0)).isEqualTo("B4");
            assertThat(n.walls()).contains(face).hasSize(3);
            assertThat(n.jumpsLeft()).containsExactly(0, 1, 1);
            assertThat(n.jumpCount()).isEqualTo(1);
            assertThat(n.moveCount()).isEqualTo(4);
            assertThat(r.events().getFirst()).isInstanceOfSatisfying(DambadukEvent.StoneMoved.class, m -> {
                assertThat(m.jumpedFace()).isEqualTo(face);
                assertThat(m.wallFace()).isEqualTo(face);
            });
            DambadukInvariantChecker.check(n);
            n = apply(n, move(1, UP, UP)).newState();                 // F3 → F2
            n = apply(n, move(2, LEFT, LEFT)).newState();             // D3 → C3
            assertThat(rejection(n, 0, jump(0, DOWN, UP))).isEqualTo(RejectionReason.JUMP_UNAVAILABLE); // 한 판에 한 번
        }

        @Test
        void a_jump_over_two_walls_is_refused_and_one_wall_is_fine() {
            DambadukState s = playing(3, 0,
                    "+---+---+---+---+---+---+---+",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| B   .   .   .   .   .   b |",
                    "+   +   +   +---+   +   +   +",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +---+   +   +   +",
                    "| .   .   .   A   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| C   .   .   .   .   .   c |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   a   .   .   . |",
                    "+---+---+---+---+---+---+---+");
            int d4 = s.stoneAt(c("D4")).orElseThrow().id();
            assertThat(rejection(s, 0, jump(d4, LEFT, UP, UP))).isEqualTo(RejectionReason.WALL_IN_PATH);
            DambadukState n = apply(s, jump(d4, DOWN, UP)).newState();                       // D3, 넘은 면에 다시
            assertThat(at(n, d4)).isEqualTo("D3");
            assertThat(n.walls()).hasSize(2);
        }
    }

    @Nested
    class SkipAndEnd {

        @Test
        void a_seat_with_no_move_is_skipped_and_the_mover_goes_again() {
            DambadukState s = playing(2, 0,
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
            DambadukInvariantChecker.check(s);
            assertThat(ENGINE.options(s, 1)).isEmpty();                                       // 백 넷 모두 갇힘
            int d4 = s.stoneAt(c("D4")).orElseThrow().id();
            DambadukEngine.Result r = apply(s, move(d4, RIGHT, RIGHT));                        // D4 → E4
            assertThat(r.events()).extracting(e -> e.getClass().getSimpleName())
                    .containsExactly("StoneMoved", "TurnSkipped", "TurnChanged");
            assertThat(r.events().get(1)).isEqualTo(new DambadukEvent.TurnSkipped(1));
            assertThat(r.newState().turnSeat()).isZero();
            DambadukInvariantChecker.check(r.newState());
        }

        @Test
        void separating_everyone_ends_the_match_rules_example_12_3() {
            DambadukState s = playing(2, 0,
                    "+---+---+---+---+---+---+---+",
                    "| .   a   . | .   .   b   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   . | B   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| A   .   . | .   .   .   B |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   b   .   . |",
                    "+   +   +   +---+---+---+---+",
                    "| .   .   A | .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   a   . | .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   . | .   .   .   . |",
                    "+---+---+---+---+---+---+---+");
            DambadukInvariantChecker.check(s);
            int c5 = s.stoneAt(c("C5")).orElseThrow().id();
            DambadukEngine.Result r = apply(s, move(c5, RIGHT, UP));                           // C5 → C4, C4|D4 를 막는다
            DambadukState n = r.newState();
            MatchResult res = n.result();
            assertThat(res.reason()).isEqualTo(EndReason.SEPARATED);
            assertThat(res.standings()).extracting(Standing::seat, Standing::score, Standing::largest, Standing::rank)
                    .containsExactly(Tuple.tuple(0, 21, 21, 1), Tuple.tuple(1, 16, 16, 2));
            assertThat(res.winners()).containsExactly(0);
            assertThat(res.draw()).isFalse();
            assertThat(res.causeSeat()).isEqualTo(-1);
            assertThat(n.phase()).isEqualTo(Phase.ENDED);
            assertThat(n.turnSeat()).isEqualTo(-1);
            assertThat(r.events()).last().isInstanceOfSatisfying(DambadukEvent.MatchEnded.class, me -> {
                assertThat(me.reason()).isEqualTo(EndReason.SEPARATED);
                assertThat(me.winners()).containsExactly(0);
                assertThat(me.moveCount()).isEqualTo(n.moveCount());
                assertThat(me.territory()).isEqualTo(ENGINE.territory(n));
            });
            DambadukInvariantChecker.check(n);
            assertThat(rejection(n, 1, move(0, UP, UP))).isEqualTo(RejectionReason.MATCH_OVER);
            assertThat(ENGINE.legalActions(n, 1)).isEmpty();
            assertThat(ENGINE.pendingSeats(n)).isEmpty();
        }

        @Test
        void a_contested_region_whose_stones_cannot_move_freezes_the_match() {
            DambadukState s = playing(2, 1,
                    "+---+---+---+---+---+---+---+",
                    "| a   .   .   .   .   .   a |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   A   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   . |",
                    "+---+---+---+---+---+---+---+",
                    "| .   .   . | A   B   .   B |",
                    "+---+---+---+---+---+---+---+",
                    "| .   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| b   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   b |",
                    "+---+---+---+---+---+---+---+");
            DambadukInvariantChecker.check(s);
            int g4 = s.stoneAt(c("G4")).orElseThrow().id();
            DambadukState n = apply(s, move(g4, LEFT, LEFT)).newState();                       // G4 → F4, E4|F4 를 막는다
            assertThat(n.result().reason()).isEqualTo(EndReason.FROZEN);
            assertThat(n.result().standings()).extracting(Standing::score, Standing::rank)
                    .containsExactly(Tuple.tuple(21, 2), Tuple.tuple(23, 1));                 // 백 = 아래 21 + F4·G4 2
            assertThat(n.result().winners()).containsExactly(1);
            assertThat(ENGINE.territory(n).hasContested()).isTrue();                            // D4·E4 는 다툼 터로 남는다
            DambadukInvariantChecker.check(n);
        }

        /** 위 3줄(흑)·가운데 3줄(백)·맨 아래 1줄(빈 터), D3/D4 만 열려 있다. {@code splitTop} 이면 1~2행과 3행 사이도 막는다. */
        private DambadukState ladder(boolean splitTop) {
            String topSep = splitTop ? "+---+---+---+---+---+---+---+" : "+   +   +   +   +   +   +   +";
            return playing(2, 0,
                    "+---+---+---+---+---+---+---+",
                    "| a   .   .   .   .   .   a |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   A   .   . |",
                    topSep,
                    "| .   .   A   .   .   .   . |",
                    "+---+---+---+   +---+---+---+",
                    "| b   .   .   .   .   .   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   B   .   .   .   B   . |",
                    "+   +   +   +   +   +   +   +",
                    "| .   .   .   .   .   .   b |",
                    "+---+---+---+---+---+---+---+",
                    "| .   .   .   .   .   .   . |",
                    "+---+---+---+---+---+---+---+");
        }

        @Test
        void equal_score_and_equal_largest_home_is_a_draw() {
            DambadukState s = ladder(false);
            DambadukInvariantChecker.check(s);
            int c3 = s.stoneAt(c("C3")).orElseThrow().id();
            DambadukState n = apply(s, move(c3, DOWN, RIGHT)).newState();                      // C3 → D3, D3/D4 를 막는다
            MatchResult res = n.result();
            assertThat(res.reason()).isEqualTo(EndReason.SEPARATED);
            assertThat(res.standings()).extracting(Standing::score, Standing::largest, Standing::rank)
                    .containsExactly(Tuple.tuple(21, 21, 1), Tuple.tuple(21, 21, 1));
            assertThat(res.draw()).isTrue();
            assertThat(res.winners()).isEmpty();
            DambadukInvariantChecker.check(n);
        }

        @Test
        void equal_score_is_broken_by_the_largest_home() {
            DambadukState s = ladder(true);
            DambadukInvariantChecker.check(s);
            int c3 = s.stoneAt(c("C3")).orElseThrow().id();
            DambadukState n = apply(s, move(c3, DOWN, RIGHT)).newState();
            MatchResult res = n.result();
            assertThat(res.standings()).extracting(Standing::score, Standing::largest, Standing::rank)
                    .containsExactly(Tuple.tuple(21, 14, 2), Tuple.tuple(21, 21, 1));           // 흑 14 + 7, 백 21 하나
            assertThat(res.winners()).containsExactly(1);
            assertThat(res.draw()).isFalse();
            DambadukInvariantChecker.check(n);
        }

        @Test
        void standings_rank_ties_together_and_put_exits_last() {
            var terr = new com.mirboard.domain.game.dambaduk.state.Territory(List.of(), List.of(10, 10, 4), List.of(5, 5, 4));
            assertThat(DambadukEngine.standings(terr, 3, List.of()))
                    .extracting(Standing::rank).containsExactly(1, 1, 3);
            assertThat(DambadukEngine.standings(terr, 3,
                    List.of(new com.mirboard.domain.game.dambaduk.state.Exit(0,
                            com.mirboard.domain.game.dambaduk.state.ExitReason.DESERTED))))
                    .extracting(Standing::rank).containsExactly(3, 1, 2);
        }
    }

    @Nested
    class Queries {

        @Test
        void legal_actions_match_the_options_times_their_walls() {
            DambadukState s = twoPlaying();
            int expected = ENGINE.options(s, 0).stream().mapToInt(o -> o.walls().size()).sum();
            List<DambadukAction> legal = ENGINE.legalActions(s, 0);
            assertThat(legal).hasSize(expected);
            for (DambadukAction a : legal) {
                ENGINE.apply(s, 0, a);                                                          // 전부 받아들여진다
            }
            assertThat(ENGINE.legalActions(s, 1)).isEmpty();
            assertThat(ENGINE.pendingSeats(s)).containsExactly(0);
        }

        @Test
        void placement_legal_actions_cover_every_empty_cell() {
            DambadukState s = DambadukStates.three();
            assertThat(ENGINE.legalActions(s, 0)).hasSize(2 * 49);
            DambadukState two = DambadukStates.two();
            assertThat(ENGINE.legalActions(two, 0)).hasSize(49 - 4);
        }

        @Test
        void the_public_view_carries_the_computed_territory_and_options() {
            DambadukState s = twoPlaying();
            var v = ENGINE.publicView(s);
            assertThat(v.territory()).isEqualTo(ENGINE.territory(s));
            assertThat(v.options()).isEqualTo(ENGINE.options(s, 0));
            assertThat(v.placeKinds()).isEmpty();
            assertThat(v.seatColors()).containsExactly(0, 1);
            var placing = ENGINE.publicView(DambadukStates.two());
            assertThat(placing.options()).isEmpty();
            assertThat(placing.placeKinds()).containsExactly(StoneKind.STRAIGHT);
        }
    }
}
