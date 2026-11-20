package com.mirboard.domain.game.starsquare.puzzle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** `docs/rules-starsquare.md` §1·§4.3 — 풀이기(생성 도구·자원 검증 전용)의 셈이 맞는가. */
class SolverTest {

    @Test
    void counts_8_for_3x3_and_7040_for_4x4() {
        assertThat(Solver.count(Grid.empty(3), 1_000, Long.MAX_VALUE).solutions()).isEqualTo(8);
        List<Grid> fours = new ArrayList<>();
        Solver.enumerate(4, fours::add);
        assertThat(fours).hasSize(7_040);
        assertThat(new HashSet<>(fours)).hasSize(7_040);
        assertThat(fours).allMatch(MagicCheck::isMagic);
    }

    @Test
    void counting_with_clues_matches_filtering_the_full_4x4_list() {
        List<Grid> fours = new ArrayList<>();
        Solver.enumerate(4, fours::add);
        SplittableRandom rng = new SplittableRandom(9);
        for (int t = 0; t < 300; t++) {
            Grid source = fours.get(rng.nextInt(fours.size()));
            int[] clues = new int[16];
            int k = 3 + rng.nextInt(5);
            for (int i = 0; i < k; i++) {
                int cell = rng.nextInt(16);
                clues[cell] = source.get(cell);
            }
            Grid cg = Grid.of(clues);
            long expected = fours.stream().filter(g -> cg.nonZeroCellsMatch(g)).count();
            Solver.Count count = Solver.count(cg, 10_000, Long.MAX_VALUE);
            assertThat(count.complete()).isTrue();
            assertThat(count.solutions()).as("clues %s", cg).isEqualTo((int) expected);
            assertThat(MagicCheck.isMagic(count.first(), cg)).isTrue();
        }
    }

    @Test
    void impossible_clues_have_no_solution() {
        // 3×3 가운데는 늘 5
        assertThat(Solver.count(Grid.of(0, 0, 0, 0, 1, 0, 0, 0, 0), 10, Long.MAX_VALUE).solutions()).isZero();
        // 다 찬 줄인데 합이 15 가 아님
        assertThat(Solver.count(Grid.of(1, 2, 3, 0, 0, 0, 0, 0, 0), 10, Long.MAX_VALUE).solutions()).isZero();
    }

    @Test
    void the_cap_and_the_node_limit_stop_the_search() {
        Solver.Count capped = Solver.count(Grid.empty(4), 5, Long.MAX_VALUE);
        assertThat(capped.solutions()).isEqualTo(5);
        assertThat(capped.complete()).isFalse();
        Solver.Count limited = Solver.count(Grid.empty(5), 1_000_000, 1_000);
        assertThat(limited.complete()).isFalse();
        assertThat(limited.nodes()).isLessThanOrEqualTo(1_001);
    }

    @Test
    void random_5x5_squares_are_magic_and_varied() {
        SplittableRandom rng = new SplittableRandom(3);
        HashSet<Grid> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Grid g = Solver.random(5, rng, 200_000);
            assertThat(MagicCheck.isMagic(g)).isTrue();
            seen.add(g);
        }
        assertThat(seen).hasSize(50);
    }

    @Test
    void line_filling_fills_forced_cells_only() {
        // 2 7 _ / _ 5 _ / _ _ _ → 윗줄 6, ↙ 대각선 6 5 _ → 4, 가운데 세로 7 5 _ → 3 … 빈 칸 6개가 끝까지 풀린다
        Grid clues = Grid.of(2, 7, 0, 0, 5, 0, 0, 0, 0);
        assertThat(Deduction.fillable(clues)).isEqualTo(6);
        assertThat(Deduction.ratio(clues)).isEqualTo(1.0);
        // 두 단서가 같은 줄에 없으면 빈 칸이 하나뿐인 줄이 없어 하나도 못 채운다
        assertThat(Deduction.fillable(Grid.of(2, 0, 0, 0, 0, 1, 0, 0, 0))).isZero();
        // 같은 대각선의 두 단서는 가운데를 정한다(2 _ 8 → 5)
        assertThat(Deduction.fillable(Grid.of(2, 0, 0, 0, 0, 0, 0, 0, 8))).isEqualTo(1);
        assertThat(Deduction.ratio(MagicCheckTest.LO_SHU)).isEqualTo(1.0);
    }
}
