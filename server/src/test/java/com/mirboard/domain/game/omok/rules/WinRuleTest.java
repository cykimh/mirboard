package com.mirboard.domain.game.omok.rules;

import static com.mirboard.domain.game.omok.OmokStates.p;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 승리 판정 (룰 §5) — 룰 3종 × 정확히 5 / 6 / 5 와 6 동시, 백 장목, 승리 줄 위치. */
class WinRuleTest {

    private static Board row(Stone color, int y, int fromX, int count) {
        Board b = Board.empty();
        for (int x = fromX; x < fromX + count; x++) {
            b = b.with(p(x, y), color);
        }
        return b;
    }

    @ParameterizedTest
    @EnumSource(OmokRule.class)
    void exact_five_wins_for_both_colors_in_every_rule(OmokRule rule) {
        assertThat(WinRule.winLine(row(Stone.BLACK, 7, 3, 5), p(5, 7), rule)).isPresent();
        assertThat(WinRule.winLine(row(Stone.WHITE, 7, 3, 5), p(5, 7), rule)).isPresent();
    }

    @Test
    void black_six_wins_only_outside_renju() {
        Board six = row(Stone.BLACK, 7, 3, 6);
        assertThat(WinRule.winLine(six, p(5, 7), OmokRule.RENJU)).isEmpty();
        assertThat(WinRule.winLine(six, p(5, 7), OmokRule.DOUBLE_THREE_ONLY)).isPresent();
        assertThat(WinRule.winLine(six, p(5, 7), OmokRule.FREESTYLE)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(OmokRule.class)
    void white_six_wins_in_every_rule(OmokRule rule) {
        assertThat(WinRule.winLine(row(Stone.WHITE, 7, 3, 6), p(5, 7), rule)).isPresent();
    }

    @Test
    void renju_black_five_counts_even_with_a_six_in_another_direction() {
        Board b = row(Stone.BLACK, 7, 3, 5);                // 가로 (3..7, 7) 정확히 5
        for (int y = 2; y <= 8; y++) {                       // 세로 (5, 2..8) — (5,7) 포함 7목
            if (y != 7) {
                b = b.with(p(5, y), Stone.BLACK);
            }
        }
        assertThat(WinRule.winLine(b, p(5, 7), OmokRule.RENJU))
                .contains(List.of(p(3, 7), p(4, 7), p(5, 7), p(6, 7), p(7, 7)));
    }

    @Test
    void win_line_is_the_run_for_five_and_centered_for_a_longer_run() {
        assertThat(WinRule.winLine(row(Stone.WHITE, 0, 0, 5), p(0, 0), OmokRule.RENJU))
                .contains(List.of(p(0, 0), p(1, 0), p(2, 0), p(3, 0), p(4, 0)));
        // 7목 (3..9) 에서 (6,7) 을 가운데로.
        assertThat(WinRule.winLine(row(Stone.WHITE, 7, 3, 7), p(6, 7), OmokRule.RENJU))
                .contains(List.of(p(4, 7), p(5, 7), p(6, 7), p(7, 7), p(8, 7)));
        // 끝 돌이면 구간 안으로 자른다.
        assertThat(WinRule.winLine(row(Stone.WHITE, 7, 3, 7), p(3, 7), OmokRule.RENJU))
                .contains(List.of(p(3, 7), p(4, 7), p(5, 7), p(6, 7), p(7, 7)));
    }

    @Test
    void four_is_not_a_win_and_directions_are_searched_in_order() {
        assertThat(WinRule.winLine(row(Stone.BLACK, 7, 3, 4), p(4, 7), OmokRule.FREESTYLE)).isEmpty();
        Board diag = Board.empty();
        for (int k = 0; k < 5; k++) {
            diag = diag.with(new Point(2 + k, 10 - k), Stone.BLACK);   // '/' 방향
        }
        assertThat(WinRule.winLine(diag, p(4, 8), OmokRule.RENJU)).get()
                .satisfies(line -> assertThat(line).contains(p(2, 10), p(6, 6)));
    }
}
