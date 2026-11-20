package com.mirboard.domain.game.starsquare.puzzle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-starsquare.md` §4.2·§15 — 대칭 16가지가 3×3 8개·4×4 7,040개 전부와 5×5 표본에서 마방진을 보존하고, 단서·정답에 같은
 * 변환이 들어가 단서 ⊂ 정답이 유지된다.
 */
class SymmetryTest {

    @Test
    void every_symmetry_preserves_every_3x3_and_4x4_magic_square() {
        for (int n = 3; n <= 4; n++) {
            List<Grid> squares = new ArrayList<>();
            Solver.enumerate(n, squares::add);
            Set<Grid> all = new HashSet<>(squares);
            for (Grid g : squares) {
                for (int s = 0; s < Symmetry.COUNT; s++) {
                    Grid t = new Symmetry(s).apply(g);
                    assertThat(MagicCheck.isMagic(t)).as("n=%d sym=%d %s", n, s, g).isTrue();
                    assertThat(all).contains(t); // 전수 목록 안에 있어야 한다(닫혀 있음)
                }
            }
        }
    }

    @Test
    void every_symmetry_preserves_sampled_5x5_magic_squares() {
        SplittableRandom rng = new SplittableRandom(5);
        for (int i = 0; i < 200; i++) {
            Grid g = Solver.random(5, rng, 200_000);
            for (int s = 0; s < Symmetry.COUNT; s++) {
                assertThat(MagicCheck.isMagic(new Symmetry(s).apply(g))).isTrue();
            }
        }
    }

    @Test
    void rotations_and_reflections_never_fix_a_magic_square_but_the_complement_sometimes_does() {
        List<Grid> squares = new ArrayList<>();
        Solver.enumerate(4, squares::add);
        int generic = 0;
        for (Grid g : squares) {
            Set<Grid> dihedral = new HashSet<>();
            Set<Grid> all = new HashSet<>();
            for (int s = 0; s < Symmetry.COUNT; s++) {
                Grid t = new Symmetry(s).apply(g);
                all.add(t);
                if (s < 8) {
                    dihedral.add(t);
                }
            }
            // 값이 모두 달라 회전·반사 8가지는 늘 서로 다르다
            assertThat(dihedral).hasSize(8);
            // 보수가 회전과 겹치는 마방진(대칭 마방진 — 마주 보는 칸의 합이 17)은 8가지, 그 밖은 16가지
            assertThat(all.size()).isIn(8, 16);
            if (all.size() == 16) {
                generic++;
            }
        }
        assertThat(generic).isPositive();
        assertThat(new Symmetry(0).apply(squares.get(0))).isEqualTo(squares.get(0));
    }

    @Test
    void clues_and_solution_transform_together_and_empty_cells_stay_empty() {
        Grid lo = MagicCheckTest.LO_SHU;
        Grid clues = Grid.of(2, 0, 0, 0, 0, 1, 0, 0, 0);
        for (int s = 0; s < Symmetry.COUNT; s++) {
            Symmetry sym = new Symmetry(s);
            Grid tc = sym.apply(clues);
            Grid ts = sym.apply(lo);
            assertThat(tc.nonZeroCount()).isEqualTo(2);
            assertThat(tc.nonZeroCellsMatch(ts)).isTrue();
            assertThat(MagicCheck.isMagic(ts, tc)).isTrue();
        }
        // 보수는 0 을 그대로 둔다
        Grid complemented = new Symmetry(8).apply(clues);
        assertThat(complemented.get(0)).isEqualTo(8);
        assertThat(complemented.get(5)).isEqualTo(9);
        assertThat(complemented.nonZeroCount()).isEqualTo(2);
    }
}
