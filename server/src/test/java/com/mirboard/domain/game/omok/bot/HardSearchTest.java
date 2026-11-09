package com.mirboard.domain.game.omok.bot;

import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static com.mirboard.domain.game.omok.OmokStates.solo;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/** 어려움 탐색의 칸 점수 갱신·전술 (D-191). */
class HardSearchTest {

    private static Set<Point> legal(OmokState s) {
        Set<Point> out = new HashSet<>();
        for (OmokAction a : ENGINE.legalActions(s, s.turnSeat())) {
            OmokAction.Place pl = (OmokAction.Place) a;
            out.add(new Point(pl.x(), pl.y()));
        }
        return out;
    }

    private static HardSearch fresh(byte[] cells, OmokRule rule, Stone me) {
        Board b = Board.empty();
        for (int q = 0; q < Board.CELLS; q++) {
            if (cells[q] != Board.EMPTY) {
                b = b.with(new Point(q % Board.SIZE, q / Board.SIZE), cells[q] == Board.BLACK ? Stone.BLACK : Stone.WHITE);
            }
        }
        return new HardSearch(new OmokBotView(b, rule, me, List.of()), Set.of(), HardSearch.DEFAULT);
    }

    private static void assertSame(HardSearch inc, HardSearch ref) {
        assertThat(inc.hash).isEqualTo(ref.hash);
        assertThat(inc.near).isEqualTo(ref.near);
        for (int c = 1; c <= 2; c++) {
            assertThat(inc.score[c]).as("score " + c).isEqualTo(ref.score[c]);
            for (int d = 0; d < 4; d++) {
                for (int q = 0; q < Board.CELLS; q++) {
                    if (inc.cells[q] == Board.EMPTY) {
                        assertThat(inc.type[c][d][q]).as("type c%d d%d q%d", c, d, q).isEqualTo(ref.type[c][d][q]);
                    }
                }
            }
        }
        for (int q = 0; q < Board.CELLS; q++) {
            if (inc.cells[q] == Board.EMPTY) {
                assertThat(inc.maybeForbidden[q]).as("maybe q%d", q).isEqualTo(ref.maybeForbidden[q]);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(OmokRule.class)
    void incremental_updates_match_a_fresh_board_after_make_and_undo(OmokRule rule) {
        Random rng = new Random(11);
        for (int game = 0; game < 20; game++) {
            OmokState s = play(solo(rule), p(7, 7), p(8, 8), p(6, 8));
            HardSearch h = new HardSearch(OmokBotView.of(s), legal(s), HardSearch.DEFAULT);
            byte[] before = h.cells.clone();
            Deque<Integer> placed = new ArrayDeque<>();
            int color = Board.code(s.nextColor());
            for (int i = 0; i < 30; i++) {
                int q;
                do {
                    q = (5 + rng.nextInt(6)) * Board.SIZE + 3 + rng.nextInt(9);
                } while (h.cells[q] != Board.EMPTY);
                h.make(q, color);
                placed.push(q);
                color = 3 - color;
                if (i % 7 == 6) {
                    assertSame(h, fresh(h.cells, rule, Stone.BLACK));
                }
            }
            while (!placed.isEmpty()) {
                h.undo(placed.pop());
            }
            assertThat(h.cells).isEqualTo(before);
            assertSame(h, fresh(before, rule, Stone.BLACK));
        }
    }

    @Test
    void hard_plays_a_double_three_win_in_freestyle() {
        // 자유 오목 흑: 가로 (7,7)(8,7) + 세로 (9,5)(9,6) — (9,7) 은 열린 3 둘(33), 백은 하나밖에 못 막는다(VCT).
        OmokState s = play(solo(OmokRule.FREESTYLE), p(7, 7), p(0, 0), p(8, 7), p(0, 2), p(9, 5), p(0, 4), p(9, 6), p(14, 14));
        HardSearch h = new HardSearch(OmokBotView.of(s), legal(s), HardSearch.DEFAULT);
        assertThat(h.vct(Board.BLACK, HardSearch.DEFAULT.vctDepth())).isGreaterThanOrEqualTo(0);
        assertThat(h.choose()).isEqualTo(p(9, 7));
    }

    @Test
    void hard_does_not_count_a_renju_double_three_as_a_win() {
        // 같은 모양을 렌주로 — (9,7) 은 흑 33 금수라 어려움이 고르지 않는다.
        OmokState s = play(solo(OmokRule.RENJU), p(7, 7), p(0, 0), p(8, 7), p(0, 2), p(9, 5), p(0, 4), p(9, 6), p(14, 14));
        HardSearch h = new HardSearch(OmokBotView.of(s), legal(s), HardSearch.DEFAULT);
        assertThat(h.choose()).isNotEqualTo(p(9, 7));
    }

    @Test
    void the_pattern_table_reads_basic_shapes() {
        // 가운데(오프셋 0)에 내 돌을 둔다고 보고 표 번호를 만든다 — 오프셋 → 칸 코드.
        assertThat(type(new int[] {-2, -1, 1, 2}, new int[0])).isEqualTo(LinePatterns.FIVE);
        assertThat(type(new int[] {-1, 1, 2}, new int[0])).isEqualTo(LinePatterns.OPEN_FOUR);
        assertThat(type(new int[] {-1, 1, 2}, new int[] {-2})).isEqualTo(LinePatterns.FOUR);
        assertThat(type(new int[] {-1, 1}, new int[0])).isEqualTo(LinePatterns.OPEN_THREE);
        assertThat(type(new int[] {-1, 1}, new int[] {-2})).isEqualTo(LinePatterns.THREE);
        assertThat(type(new int[] {1}, new int[0])).isEqualTo(LinePatterns.OPEN_TWO);
        assertThat(type(new int[0], new int[0])).isEqualTo(LinePatterns.NONE);
        // 렌주 흑: 여섯은 장목, ○●●●●_● 의 빈칸은 6 이 되므로 4 가 아니다.
        assertThat(LinePatterns.EXACT_FIVE[index(new int[] {-3, -2, -1, 1, 2}, new int[0])]).isEqualTo(LinePatterns.OVERLINE);
    }

    private static byte type(int[] own, int[] blocked) {
        return LinePatterns.AT_LEAST_FIVE[index(own, blocked)];
    }

    private static int index(int[] own, int[] blocked) {
        int idx = 0;
        for (int i = 0; i < 10; i++) {
            int k = LinePatterns.OFFSETS[i];
            int code = 0;
            for (int o : own) {
                if (o == k) {
                    code = 1;
                }
            }
            for (int b : blocked) {
                if (b == k) {
                    code = 2;
                }
            }
            idx += code * LinePatterns.POW3[i];
        }
        return idx;
    }
}
