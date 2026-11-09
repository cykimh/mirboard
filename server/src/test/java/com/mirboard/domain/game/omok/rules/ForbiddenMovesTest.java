package com.mirboard.domain.game.omok.rules;

import static com.mirboard.domain.game.omok.OmokBoards.diagram;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.OmokBoards.Diagram;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Point;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 금수 고정 국면 (룰 §6.4) — 정답을 손으로 따진 국면 30개. {@code X} 흑, {@code O} 백, {@code *} 판정할 빈칸. 그림의 왼쪽 위가
 * 판 (3,3)(따로 적은 것 제외). 각 국면 주석에 "왜 그 답인가"를 적어 둔다 — 테스트가 구현을 따라 쓰이지 않게.
 */
class ForbiddenMovesTest {

    private static Optional<ForbiddenKind> judge(Diagram d, OmokRule rule) {
        Optional<ForbiddenKind> one = ForbiddenRule.check(d.board(), d.star(), rule);
        // 한 자리 판정과 판 전체 목록이 같은 답을 내야 한다(공개 뷰·검증이 같은 함수를 쓴다).
        Optional<ForbiddenKind> inAll = ForbiddenRule.all(d.board(), rule).stream()
                .filter(f -> f.x() == d.star().x() && f.y() == d.star().y())
                .map(ForbiddenPoint::kind)
                .findFirst();
        assertThat(inAll).isEqualTo(one);
        return one;
    }

    private static Optional<ForbiddenKind> renju(Diagram d) {
        return judge(d, OmokRule.RENJU);
    }

    // ---------- 국면 ----------

    /** 가로 {@code XX*} + 세로 {@code XX*} — 양 끝이 두 칸씩 비어 둘 다 열린 3. */
    static final Diagram B1 = diagram(
            ".........",
            ".........",
            "....X....",
            "....X....",
            "..XX*....",
            ".........",
            ".........");

    /** 띈 3 {@code .X.*X.}(가운데를 채우면 열린 4) + {@code \} 대각 3. */
    static final Diagram B2 = diagram(
            ".........",
            ".........",
            "..X......",
            "...X.....",
            "..X.*X...",
            ".........",
            ".........");

    /** 두 대각선 {@code \}·{@code /} 의 3. */
    static final Diagram B3 = diagram(
            ".........",
            ".........",
            "..X...X..",
            "...X.X...",
            "....*....",
            ".........",
            ".........");

    /** 세로 띈 3 + {@code /} 대각 3. */
    static final Diagram B4 = diagram(
            ".........",
            ".........",
            "....X....",
            ".........",
            "....*....",
            "...XX....",
            "..X......",
            ".........",
            ".........");

    /** 가로 {@code OXX*..} — 왼쪽이 막혀 열린 4 를 못 만든다 → 3 은 세로 하나뿐. */
    static final Diagram N1 = diagram(
            ".........",
            ".........",
            "....X....",
            "....X....",
            ".OXX*....",
            ".........",
            ".........");

    /** 가로 {@code O.XX*.O} — 어느 쪽을 채워도 반대쪽이 백이라 막힌 4 → 3 아님. */
    static final Diagram N2 = diagram(
            ".........",
            ".........",
            "....X....",
            "....X....",
            "O.XX*.O..",
            ".........",
            ".........");

    /** 4·3 — 가로는 막힌 4({@code OXXX*.}), 세로는 3. 4 하나 + 3 하나는 금수가 아니다. */
    static final Diagram N3 = diagram(
            ".........",
            ".........",
            "....X....",
            "....X....",
            "OXXX*....",
            ".........",
            ".........");

    /** 판 가장자리 — 가로 {@code |XX*..} 는 벽에 막혀 3 이 아니다(원점 (0,3)). */
    static final Diagram N4 = diagram(0, 3,
            "..X......",
            "..X......",
            "XX*......",
            ".........",
            ".........");

    /**
     * 거짓 33(장목) — 가로 {@code O.XX*} 의 열린 4 자리는 (8,7) 하나뿐인데, 그 자리는 세로 6목(장목)이 돼 흑에게 금수다.
     * 그래서 가로는 3 이 아니고 남은 3 은 세로 하나 → 금수 아님.
     */
    static final Diagram F1 = diagram(
            "..........",
            ".....X....",
            "....XX....",
            "....XX....",
            "O.XX*.....",
            ".....X....",
            ".....X....",
            "..........",
            "..........");

    /** F1 에서 왼쪽 백을 치우면 (4,7) 로도 열린 4 를 만들 수 있다(그 자리는 금수 아님) → 진짜 33. */
    static final Diagram F1_OPEN = diagram(
            "..........",
            ".....X....",
            "....XX....",
            "....XX....",
            "..XX*.....",
            ".....X....",
            ".....X....",
            "..........",
            "..........");

    /**
     * 거짓 33(33) — 가로 {@code O.XX*} 의 열린 4 자리 (8,7) 이 세로 3 + {@code /} 3 을 동시에 만들어 33 금수다. 가로는 3 이
     * 아니고 남은 3 은 세로 하나 → 금수 아님.
     */
    static final Diagram F2 = diagram(
            "..........",
            "..........",
            "....XX.X..",
            "....XXX...",
            "O.XX*.....",
            "..........",
            "..........",
            "..........",
            "..........");

    /** F2 에서 왼쪽 백을 치우면 (4,7) 이 금수 아닌 열린 4 자리 → 진짜 33. */
    static final Diagram F2_OPEN = diagram(
            "..........",
            "..........",
            "....XX.X..",
            "....XXX...",
            "..XX*.....",
            "..........",
            "..........",
            "..........",
            "..........");

    /** 가로 열린 4 + 세로 열린 4. */
    static final Diagram Q1 = diagram(
            ".........",
            "....X....",
            "....X....",
            "....X....",
            ".XXX*....",
            ".........",
            ".........");

    /** 한 줄 44 {@code X.X*X.X} — 왼쪽·오른쪽 빈칸이 서로 다른 네 돌로 5 를 만든다. */
    static final Diagram Q2 = diagram(
            ".........",
            ".........",
            "..........",
            "..........",
            "X.X*X.X..",
            ".........",
            ".........");

    /** 한 줄 44 {@code XX.*X.XX}. */
    static final Diagram Q3 = diagram(
            ".........",
            ".........",
            ".........",
            ".........",
            "XX.*X.XX.",
            ".........",
            ".........");

    /** 가로 열린 4 + 세로 막힌 4(위가 백). 열린·막힌을 가리지 않고 4 두 개면 44. */
    static final Diagram Q4 = diagram(
            "....O....",
            "....X....",
            "....X....",
            "....X....",
            ".XXX*....",
            ".........",
            ".........");

    /** 열린 4 하나뿐 — 금수 아님(두 이기는 자리가 같은 네 돌이라 4 하나). */
    static final Diagram Q5 = diagram(
            ".........",
            ".........",
            ".........",
            ".........",
            ".XXX*....",
            ".........",
            ".........");

    /** Q1 + 두 대각선 3 — 44 와 33 이 겹치면 44 로 적는다(룰 §6.1). */
    static final Diagram Q1_WITH_THREES = diagram(
            ".........",
            "....X....",
            "..X.X.X..",
            "...XXX...",
            ".XXX*....",
            ".........",
            ".........");

    /** 가로 6목 {@code XXX*XX}. */
    static final Diagram L1 = diagram(
            ".........",
            ".........",
            ".........",
            ".........",
            "XXX*XX...",
            ".........",
            ".........");

    /** 가로 7목. */
    static final Diagram L2 = diagram(
            ".........",
            ".........",
            ".........",
            ".........",
            "XXX*XXX..",
            ".........",
            ".........");

    /** {@code \} 대각 6목. */
    static final Diagram L3 = diagram(
            "X........",
            ".X.......",
            "..X......",
            "...*.....",
            "....X....",
            ".....X...",
            ".........");

    /** 5목 + 33 — 5 가 먼저라 둘 수 있다(이긴다). */
    static final Diagram P1 = diagram(
            ".........",
            ".........",
            "..X.X....",
            "...XX....",
            "XXXX*....",
            ".........",
            ".........");

    /** 가로 정확히 5 + 세로 6 — 5 가 있으면 장목이 같이 생겨도 이긴다. */
    static final Diagram P2 = diagram(
            ".........",
            "....X....",
            "....X....",
            "....X....",
            "XXXX*....",
            "....X....",
            "....X....",
            ".........");

    /** 5목 + 다른 두 방향의 4(44 모양) — 5 가 먼저. */
    static final Diagram P3 = diagram(
            ".........",
            "....X..X.",
            "....X.X..",
            "....XX...",
            "XXXX*....",
            ".........",
            ".........");

    @Nested
    class BasicDoubleThree {
        @Test
        void horizontal_and_vertical() {
            assertThat(renju(B1)).contains(ForbiddenKind.DOUBLE_THREE);
        }

        @Test
        void split_three_and_diagonal() {
            assertThat(renju(B2)).contains(ForbiddenKind.DOUBLE_THREE);
        }

        @Test
        void two_diagonals() {
            assertThat(renju(B3)).contains(ForbiddenKind.DOUBLE_THREE);
        }

        @Test
        void vertical_split_three_and_rising_diagonal() {
            assertThat(renju(B4)).contains(ForbiddenKind.DOUBLE_THREE);
        }
    }

    @Nested
    class NotDoubleThree {
        @Test
        void one_end_blocked_by_white() {
            assertThat(renju(N1)).isEmpty();
        }

        @Test
        void both_far_ends_white() {
            assertThat(renju(N2)).isEmpty();
        }

        @Test
        void four_three_is_allowed() {
            assertThat(renju(N3)).isEmpty();
        }

        @Test
        void board_edge_blocks_the_three() {
            assertThat(renju(N4)).isEmpty();
        }
    }

    @Nested
    class FalseDoubleThree {
        @Test
        void completion_point_is_an_overline() {
            assertThat(renju(F1)).isEmpty();
        }

        @Test
        void same_shape_without_the_white_stone_is_a_real_double_three() {
            assertThat(renju(F1_OPEN)).contains(ForbiddenKind.DOUBLE_THREE);
        }

        @Test
        void completion_point_is_a_double_three() {
            assertThat(renju(F2)).isEmpty();
        }

        @Test
        void same_shape_without_the_white_stone_is_a_real_double_three_too() {
            assertThat(renju(F2_OPEN)).contains(ForbiddenKind.DOUBLE_THREE);
        }
    }

    @Nested
    class DoubleFour {
        @Test
        void two_directions() {
            assertThat(renju(Q1)).contains(ForbiddenKind.DOUBLE_FOUR);
        }

        @Test
        void one_line_split() {
            assertThat(renju(Q2)).contains(ForbiddenKind.DOUBLE_FOUR);
        }

        @Test
        void one_line_pairs() {
            assertThat(renju(Q3)).contains(ForbiddenKind.DOUBLE_FOUR);
        }

        @Test
        void straight_four_plus_closed_four() {
            assertThat(renju(Q4)).contains(ForbiddenKind.DOUBLE_FOUR);
        }

        @Test
        void a_single_straight_four_is_allowed() {
            assertThat(renju(Q5)).isEmpty();
        }

        @Test
        void double_four_wins_the_label_over_double_three() {
            assertThat(renju(Q1_WITH_THREES)).contains(ForbiddenKind.DOUBLE_FOUR);
        }
    }

    @Nested
    class Overline {
        @Test
        void six() {
            assertThat(renju(L1)).contains(ForbiddenKind.OVERLINE);
        }

        @Test
        void seven() {
            assertThat(renju(L2)).contains(ForbiddenKind.OVERLINE);
        }

        @Test
        void diagonal_six() {
            assertThat(renju(L3)).contains(ForbiddenKind.OVERLINE);
        }
    }

    @Nested
    class FiveComesFirst {
        @Test
        void five_with_double_three() {
            assertThat(renju(P1)).isEmpty();
        }

        @Test
        void exact_five_with_an_overline_elsewhere() {
            assertThat(renju(P2)).isEmpty();
        }

        @Test
        void five_with_double_four() {
            assertThat(renju(P3)).isEmpty();
        }
    }

    @Nested
    class OtherRules {
        @Test
        void double_three_only_allows_double_four() {
            assertThat(judge(Q1, OmokRule.DOUBLE_THREE_ONLY)).isEmpty();
        }

        @Test
        void freestyle_allows_double_four() {
            assertThat(judge(Q1, OmokRule.FREESTYLE)).isEmpty();
        }

        @Test
        void freestyle_allows_double_three() {
            assertThat(judge(B1, OmokRule.FREESTYLE)).isEmpty();
        }

        @Test
        void double_three_only_forbids_double_three() {
            assertThat(judge(B1, OmokRule.DOUBLE_THREE_ONLY)).contains(ForbiddenKind.DOUBLE_THREE);
        }

        @Test
        void double_three_only_lets_black_win_with_six() {
            assertThat(judge(L1, OmokRule.DOUBLE_THREE_ONLY)).isEmpty();
        }

        @Test
        void a_false_three_in_renju_is_real_when_overlines_are_allowed() {
            // F1 의 열린 4 자리는 렌주에선 장목 금수지만 삼삼만 룰에선 6목 승리라 금수가 아니다 → 가로도 진짜 3.
            assertThat(judge(F1, OmokRule.DOUBLE_THREE_ONLY)).contains(ForbiddenKind.DOUBLE_THREE);
        }
    }

    @Test
    void an_occupied_point_is_never_reported() {
        Point occupied = new Point(B1.star().x() - 1, B1.star().y());
        assertThat(ForbiddenRule.check(B1.board(), occupied, OmokRule.RENJU)).isEmpty();
    }
}
