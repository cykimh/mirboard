package com.mirboard.domain.game.starsquare.puzzle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** `docs/rules-starsquare.md` §1·§5 — 합 M, 단서 일치, 진행도(중복 숫자는 세지 않음). */
class MagicCheckTest {

    static final Grid LO_SHU = Grid.of(2, 7, 6, 9, 5, 1, 4, 3, 8);

    @Test
    void magic_sums_are_15_34_65() {
        assertThat(MagicCheck.magicSum(3)).isEqualTo(15);
        assertThat(MagicCheck.magicSum(4)).isEqualTo(34);
        assertThat(MagicCheck.magicSum(5)).isEqualTo(65);
        assertThat(Lines.of(3).count()).isEqualTo(8);
        assertThat(Lines.of(4).count()).isEqualTo(10);
        assertThat(Lines.of(5).count()).isEqualTo(12);
    }

    @Test
    void a_magic_square_must_also_match_the_clues() {
        Grid clues = Grid.of(2, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(MagicCheck.isMagic(LO_SHU, clues)).isTrue();
        assertThat(MagicCheck.isMagic(LO_SHU, Grid.of(4, 0, 0, 0, 0, 0, 0, 0, 0))).isFalse();
        // 단서와 맞는 다른 마방진도 정답이다(우리 정답과 같지 않아도) — §5
        Grid reflected = new Symmetry(6).apply(LO_SHU); // ↘ 대각선 반사: 왼쪽 위 2 는 그대로
        assertThat(reflected).isNotEqualTo(LO_SHU);
        assertThat(MagicCheck.isMagic(reflected, clues)).isTrue();
        // 크기가 다르면 아니다
        assertThat(MagicCheck.isMagic(LO_SHU, Grid.empty(4))).isFalse();
    }

    @Test
    void not_magic_when_a_value_repeats_or_is_missing() {
        assertThat(MagicCheck.isMagic(Grid.of(5, 5, 5, 5, 5, 5, 5, 5, 5))).isFalse();
        assertThat(MagicCheck.isMagic(LO_SHU.with(0, 0))).isFalse();
        assertThat(MagicCheck.isMagic(Grid.of(1, 2, 3, 4, 5, 6, 7, 8, 9))).isFalse();
    }

    @Test
    void progress_counts_only_values_used_once() {
        // 전부 5 — 줄 합은 모두 15 지만 중복이라 채운 칸·맞은 줄 0 (§5.1 꼼수 방지)
        assertThat(MagicCheck.progress(Grid.of(5, 5, 5, 5, 5, 5, 5, 5, 5))).isEqualTo(new Progress(0, 0));
        // 빈 판
        assertThat(MagicCheck.progress(Grid.empty(3))).isEqualTo(new Progress(0, 0));
        // 완성
        assertThat(MagicCheck.progress(LO_SHU)).isEqualTo(new Progress(9, 8));
        // 맨 윗줄만 맞음(2 7 6) + 가운데 5 — 가로 0 하나
        assertThat(MagicCheck.progress(Grid.of(2, 7, 6, 0, 5, 0, 0, 0, 0))).isEqualTo(new Progress(4, 1));
        // 왼쪽 위 2 를 아래에도 한 번 더 — 두 2 는 세지 않아 윗줄이 깨진다
        assertThat(MagicCheck.progress(Grid.of(2, 7, 6, 0, 5, 0, 0, 0, 2))).isEqualTo(new Progress(3, 0));
        // 합이 15 지만 마방진이 아닌 줄도 맞은 줄이다(줄 단위 표시) — 1 5 9 / 가운데 줄
        assertThat(MagicCheck.progress(Grid.of(0, 0, 0, 1, 5, 9, 0, 0, 0))).isEqualTo(new Progress(3, 1));
    }

    @Test
    void complete_progress_is_the_same_as_magic_on_4x4_squares_and_their_neighbours() {
        List<Grid> squares = new ArrayList<>();
        Solver.enumerate(4, squares::add);
        SplittableRandom rng = new SplittableRandom(41);
        for (Grid g : squares) {
            assertThat(MagicCheck.progress(g).complete(4)).isTrue();
            // 두 칸을 바꾼 이웃은 (거의 늘) 마방진이 아니고, 진행도 완성도 그와 같다
            int a = rng.nextInt(16);
            int b = rng.nextInt(16);
            int[] cells = g.toArray();
            int t = cells[a];
            cells[a] = cells[b];
            cells[b] = t;
            Grid swapped = Grid.of(cells);
            assertThat(MagicCheck.progress(swapped).complete(4)).isEqualTo(MagicCheck.isMagic(swapped));
        }
    }

    @Test
    void grid_rejects_unsupported_shapes_and_values() {
        assertThatThrownBy(() -> Grid.of(1, 2, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.of(new int[36])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.of(10, 0, 0, 0, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.of(-1, 0, 0, 0, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Grid.sideOf(16)).isEqualTo(4);
        assertThat(Grid.sideOf(49)).isEqualTo(-1);
    }
}
