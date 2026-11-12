package com.mirboard.domain.game.omok;

import static com.mirboard.domain.game.omok.OmokBoards.diagram;
import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.blackToMove;
import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static com.mirboard.domain.game.omok.OmokStates.solo;
import static com.mirboard.domain.game.omok.OmokStates.teams;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.omok.OmokBoards.Diagram;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.action.OmokActionRejectedException;
import com.mirboard.domain.game.omok.action.RejectionReason;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.rules.ForbiddenKind;
import com.mirboard.domain.game.omok.rules.ForbiddenPoint;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import com.mirboard.domain.game.omok.rules.WinRule;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.Move;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 시작·차례·거절·5목·기권·무승부 (룰 §3~§7, §10). */
class OmokEnginePlayTest {

    /** 가로·세로 열린 3 이 만나는 33 자리 ({@code ForbiddenMovesTest.B1} 과 같은 그림). */
    private static final Diagram B1 = diagram(
            ".........",
            ".........",
            "....X....",
            "....X....",
            "..XX*....",
            ".........",
            ".........");

    private static OmokEngine.Result place(OmokState s, int seat, int x, int y) {
        return ENGINE.apply(s, seat, new OmokAction.Place(x, y));
    }

    private static void rejected(OmokState s, int seat, OmokAction a, RejectionReason reason) {
        assertThatThrownBy(() -> ENGINE.apply(s, seat, a))
                .isInstanceOfSatisfying(OmokActionRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.code()).isEqualTo(reason.name());
                });
    }

    // ---------- 시작 ----------

    @Test
    void start_puts_the_first_seat_on_turn_with_an_empty_board() {
        OmokState s = teams(OmokRule.RENJU);
        assertThat(s.turnSeat()).isEqualTo(2);
        assertThat(s.moves()).isEmpty();
        assertThat(s.timeouts()).containsExactly(0, 0, 0, 0);
        assertThat(ENGINE.pendingSeats(s)).containsExactly(2);
    }

    @Test
    void start_rejects_a_seat_count_that_does_not_match_the_mode_or_a_first_seat_off_the_black_team() {
        assertThatThrownBy(() -> ENGINE.startMatch(OmokMode.TEAMS_2V2, OmokRule.RENJU, 3, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ENGINE.startMatch(OmokMode.SOLO, OmokRule.RENJU, 2, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ENGINE.startMatch(OmokMode.TEAMS_2V2, OmokRule.RENJU, 4, 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void first_move_is_free_and_announces_the_white_turn() {
        OmokEngine.Result r = place(solo(OmokRule.RENJU), 0, 0, 0);
        assertThat(r.events()).containsExactly(
                new OmokEvent.StonePlaced(0, Stone.BLACK, 0, 0, 1, false),
                new OmokEvent.TurnChanged(1, Stone.WHITE, List.of()));
        assertThat(r.newState().turnSeat()).isEqualTo(1);
    }

    // ---------- 2:2 차례 ----------

    @Test
    void teams_alternate_color_and_person_by_seat_order() {
        OmokState s = teams(OmokRule.FREESTYLE);
        List<Integer> seats = new ArrayList<>();
        List<Stone> colors = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            OmokEngine.Result r = place(s, s.turnSeat(), i, 0);
            OmokEvent.StonePlaced placed = (OmokEvent.StonePlaced) r.events().getFirst();
            seats.add(placed.seat());
            colors.add(placed.color());
            s = r.newState();
        }
        assertThat(seats).containsExactly(2, 3, 0, 1, 2, 3, 0, 1);
        assertThat(colors).containsExactly(Stone.BLACK, Stone.WHITE, Stone.BLACK, Stone.WHITE,
                Stone.BLACK, Stone.WHITE, Stone.BLACK, Stone.WHITE);
    }

    @Test
    void a_team_five_wins_for_both_team_members() {
        OmokState s = teams(OmokRule.RENJU);
        // 흑 팀(0·2)이 (0..4, 7), 백 팀은 (0..3, 9).
        s = play(s, p(0, 7), p(0, 9), p(1, 7), p(1, 9), p(2, 7), p(2, 9), p(3, 7), p(3, 9));
        OmokEngine.Result r = place(s, s.turnSeat(), 4, 7);
        assertThat(r.newState().result().winnerSeats()).containsExactly(0, 2);
        assertThat(r.newState().result().winnerColor()).isEqualTo(Stone.BLACK);
        assertThat(r.events().getLast()).isEqualTo(new OmokEvent.MatchEnded(Stone.BLACK, List.of(0, 2), EndReason.FIVE,
                -1, List.of(p(0, 7), p(1, 7), p(2, 7), p(3, 7), p(4, 7)), 9));
        assertThat(r.newState().turnSeat()).isEqualTo(-1);
        assertThat(ENGINE.pendingSeats(r.newState())).isEmpty();
    }

    // ---------- 거절 ----------

    @Test
    void rejects_moves_out_of_turn_off_board_and_on_stones() {
        OmokState s = play(solo(OmokRule.RENJU), p(7, 7));
        rejected(s, 0, new OmokAction.Place(1, 1), RejectionReason.NOT_YOUR_TURN);
        rejected(s, 5, new OmokAction.Place(1, 1), RejectionReason.NOT_YOUR_TURN);
        rejected(s, 1, new OmokAction.Place(Board.SIZE, 0), RejectionReason.OUT_OF_BOARD);
        rejected(s, 1, new OmokAction.Place(-1, 3), RejectionReason.OUT_OF_BOARD);
        rejected(s, 1, new OmokAction.Place(7, 7), RejectionReason.OCCUPIED);
    }

    @Test
    void rejects_black_on_a_forbidden_point_but_lets_the_same_point_be_played_in_freestyle() {
        Diagram b1 = B1;
        OmokState renju = blackToMove(OmokRule.RENJU, b1.board());
        rejected(renju, 0, new OmokAction.Place(b1.star().x(), b1.star().y()), RejectionReason.FORBIDDEN_MOVE);
        OmokState free = blackToMove(OmokRule.FREESTYLE, b1.board());
        assertThat(place(free, 0, b1.star().x(), b1.star().y()).newState().moves()).hasSize(free.moves().size() + 1);
    }

    @Test
    void white_has_no_forbidden_points() {
        // 흑 그림을 색만 바꿔 백 차례로 — 같은 33 자리에 백은 둘 수 있다.
        Diagram d = diagram(
                ".........",
                ".........",
                "....O....",
                "....O....",
                "..OO*....",
                ".........",
                ".........");
        OmokState whiteToMove = play(ENGINE.startMatch(OmokMode.SOLO, OmokRule.RENJU, 2, 0, 0),
                p(0, 0), p(7, 5), p(0, 2), p(7, 6), p(0, 4), p(5, 7), p(0, 6), p(6, 7), p(14, 14));
        assertThat(whiteToMove.colorOf(whiteToMove.turnSeat())).isEqualTo(Stone.WHITE);
        assertThat(whiteToMove.board().stoneAt(p(7, 5))).isEqualTo(Stone.WHITE);
        assertThat(place(whiteToMove, 1, d.star().x(), d.star().y()).newState().result()).isNull();
    }

    @Test
    void the_turn_change_to_black_carries_the_forbidden_points() {
        Diagram b1 = B1;
        OmokState s = blackToMove(OmokRule.RENJU, b1.board());
        // 흑이 멀리 한 수, 백이 멀리 한 수 → 다시 흑 차례 이벤트에 금수 목록.
        s = place(s, 0, 14, 7).newState();
        OmokEngine.Result r = place(s, 1, 14, 9);
        OmokEvent.TurnChanged turn = (OmokEvent.TurnChanged) r.events().getLast();
        assertThat(turn.color()).isEqualTo(Stone.BLACK);
        assertThat(turn.forbidden()).contains(new ForbiddenPoint(b1.star().x(), b1.star().y(), ForbiddenKind.DOUBLE_THREE));
        assertThat(ENGINE.forbiddenPoints(r.newState())).isEqualTo(turn.forbidden());
        assertThat(ENGINE.legalPoints(r.newState())).doesNotContain(b1.star());
    }

    @Test
    void rejects_everything_after_the_match_is_over() {
        OmokState over = ENGINE.apply(solo(OmokRule.RENJU), 1, new OmokAction.Resign()).newState();
        rejected(over, 0, new OmokAction.Place(0, 0), RejectionReason.MATCH_OVER);
        rejected(over, 0, new OmokAction.Resign(), RejectionReason.MATCH_OVER);
        assertThat(ENGINE.legalActions(over, 0)).isEmpty();
    }

    // ---------- 기권 ----------

    @Test
    void resign_works_out_of_turn_and_hands_the_win_to_the_other_side() {
        OmokEngine.Result r = ENGINE.apply(solo(OmokRule.RENJU), 1, new OmokAction.Resign());
        assertThat(r.events()).containsExactly(new OmokEvent.MatchEnded(Stone.BLACK, List.of(0), EndReason.RESIGN, 1,
                List.of(), 0));
    }

    @Test
    void a_team_member_resigning_loses_for_the_whole_team() {
        OmokState s = teams(OmokRule.RENJU);                          // 흑 팀 0·2
        OmokEngine.Result r = ENGINE.apply(s, 0, new OmokAction.Resign());
        assertThat(r.newState().result().winnerSeats()).containsExactly(1, 3);
        assertThat(r.newState().result().winnerColor()).isEqualTo(Stone.WHITE);
        assertThat(r.newState().result().causeSeat()).isEqualTo(0);
    }

    // ---------- 무승부 ----------

    /** 줄무늬 판 — 칸 (x,y) 의 색이 {@code (x/2 + y) % 2}. 어느 방향으로도 2개를 넘게 잇지 않는다(흑 113·백 112). */
    private static Stone stripe(int x, int y) {
        return (x / 2 + y) % 2 == 0 ? Stone.BLACK : Stone.WHITE;
    }

    private static OmokState fromBoard(OmokRule rule, Stone[][] colors, Point leaveEmpty) {
        List<Point> blacks = new ArrayList<>();
        List<Point> whites = new ArrayList<>();
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                if (new Point(x, y).equals(leaveEmpty)) {
                    continue;
                }
                (colors[y][x] == Stone.BLACK ? blacks : whites).add(new Point(x, y));
            }
        }
        assertThat(blacks).hasSameSizeAs(whites);                     // 흑 차례
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < blacks.size(); i++) {
            moves.add(new Move(0, blacks.get(i).x(), blacks.get(i).y(), false));
            moves.add(new Move(1, whites.get(i).x(), whites.get(i).y(), false));
        }
        return new OmokState(OmokMode.SOLO, rule, 0, 0, moves, 0, null, null);
    }

    private static Stone[][] stripes() {
        Stone[][] c = new Stone[Board.SIZE][Board.SIZE];
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                c[y][x] = stripe(x, y);
            }
        }
        return c;
    }

    @Test
    void filling_the_last_point_without_a_five_is_a_draw() {
        Point last = p(0, 0);                                         // 줄무늬에서 흑 칸
        OmokState s = fromBoard(OmokRule.FREESTYLE, stripes(), last);
        assertThat(s.moves()).hasSize(Board.CELLS - 1);
        OmokEngine.Result r = place(s, 0, last.x(), last.y());
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.BOARD_FULL);
        assertThat(r.newState().result().isDraw()).isTrue();
        assertThat(r.events().getLast()).isEqualTo(new OmokEvent.MatchEnded(null, List.of(), EndReason.BOARD_FULL, -1,
                List.of(), Board.CELLS));
    }

    @Test
    void black_with_only_forbidden_points_left_is_a_draw() {
        // 줄무늬를 고쳐 맨 윗줄에 XXX_XX 를 만들고(빈칸 (3,0) 은 장목), 흑·백 수를 맞추려고 흑 둘을 백으로 바꾼다.
        Stone[][] c = stripes();
        c[0][2] = Stone.BLACK;
        c[14][0] = Stone.WHITE;
        c[13][14] = Stone.WHITE;
        Point empty = p(3, 0);
        // 빈칸 둘((3,0)·(14,0) — 줄무늬에선 둘 다 백 칸)을 남긴 백 차례에서 백이 (14,0) 을 채우면 흑에게 (3,0) 하나만 남는다.
        Point whiteLast = p(14, 0);                                    // 줄무늬에서 백 칸(맨 윗줄 끝)
        OmokState beforeWhite = whiteToMoveWithTwoEmpty(c, empty, whiteLast);
        // 전제 확인 — 판에 5목이 없고, (3,0) 이 흑에게 장목 금수.
        Board afterWhite = beforeWhite.board().with(whiteLast, Stone.WHITE);
        assertThat(ForbiddenRule.check(afterWhite, empty, OmokRule.RENJU)).contains(ForbiddenKind.OVERLINE);
        for (Move m : beforeWhite.moves()) {
            assertThat(WinRule.winLine(afterWhite, m.point(), OmokRule.RENJU)).as("five at " + m).isEmpty();
        }
        OmokEngine.Result r = place(beforeWhite, 1, whiteLast.x(), whiteLast.y());
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.NO_LEGAL_MOVE);
        assertThat(r.newState().result().isDraw()).isTrue();
    }

    /** colors 대로 두되 두 칸을 비운 백 차례 상태(흑 112, 백 111). */
    private static OmokState whiteToMoveWithTwoEmpty(Stone[][] colors, Point emptyA, Point emptyB) {
        List<Point> blacks = new ArrayList<>();
        List<Point> whites = new ArrayList<>();
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                Point pt = new Point(x, y);
                if (pt.equals(emptyA) || pt.equals(emptyB)) {
                    continue;
                }
                (colors[y][x] == Stone.BLACK ? blacks : whites).add(pt);
            }
        }
        assertThat(blacks).hasSize(whites.size() + 1);
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < blacks.size(); i++) {
            moves.add(new Move(0, blacks.get(i).x(), blacks.get(i).y(), false));
            if (i < whites.size()) {
                moves.add(new Move(1, whites.get(i).x(), whites.get(i).y(), false));
            }
        }
        return new OmokState(OmokMode.SOLO, OmokRule.RENJU, 0, 0, moves, 1, null, null);
    }
}
