package com.mirboard.domain.game.dambaduk.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.DambadukBoards;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.state.Region;
import com.mirboard.domain.game.dambaduk.state.RegionKind;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import com.mirboard.domain.game.dambaduk.state.Territory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 터 나누기 표 테스트 (룰 §1·§5·§15) — 담만이 터를 가르고, 터마다 집·다툼·빈 터를 가린다. */
class TerritoryRuleTest {

    private static final String[] EXAMPLE_12_3 = {
            "      A   B   C   D   E   F   G",
            "    +---+---+---+---+---+---+---+",
            "  1 | .   x   . | .   .   o   . |",
            "    +   +   +   +   +   +   +   +",
            "  2 | .   .   . | o   .   .   . |",
            "    +   +   +   +   +   +   +   +",
            "  3 | x   .   . | .   .   .   o |",
            "    +   +   +   +   +   +   +   +",
            "  4 | .   .   x | .   o   .   . |",
            "    +   +   +   +---+---+---+---+",
            "  5 | .   .   . | .   .   .   . |",
            "    +   +   +   +   +   +   +   +",
            "  6 | .   x   . | .   .   .   . |",
            "    +   +   +   +   +   +   +   +",
            "  7 | .   .   . | .   .   .   . |",
            "    +---+---+---+---+---+---+---+"};

    @Test
    void an_empty_board_is_one_region_and_stones_do_not_split_it() {
        Territory t = TerritoryRule.compute(Walls.NONE, List.of(
                new Stone(0, 0, StoneKind.STRAIGHT, 3, 0), new Stone(1, 0, StoneKind.BENT, 3, 1),
                new Stone(2, 0, StoneKind.STRAIGHT, 3, 2), new Stone(3, 0, StoneKind.BENT, 3, 3)), 2);
        assertThat(t.regions()).hasSize(1);
        assertThat(t.regions().getFirst().kind()).isEqualTo(RegionKind.HOME);                // 한 사람 돌만 — 판 전체가 집
        assertThat(t.scores()).containsExactly(49, 0);
        assertThat(t.largest()).containsExactly(49, 0);
        assertThat(t.hasContested()).isFalse();
    }

    @Test
    void rules_example_12_3_three_regions_black_21_white_16() {
        DambadukBoards.Diagram d = DambadukBoards.parse(EXAMPLE_12_3);
        assertThat(d.walls().count()).isEqualTo(11);
        Territory t = TerritoryRule.compute(d.walls(), d.stones(), 2);
        assertThat(t.regions()).extracting(Region::size, Region::kind, Region::owner).containsExactly(
                org.assertj.core.groups.Tuple.tuple(21, RegionKind.HOME, 0),
                org.assertj.core.groups.Tuple.tuple(16, RegionKind.HOME, 1),
                org.assertj.core.groups.Tuple.tuple(12, RegionKind.EMPTY, -1));
        assertThat(t.scores()).containsExactly(21, 16);
        assertThat(t.largest()).containsExactly(21, 16);
        assertThat(t.hasContested()).isFalse();
    }

    @Test
    void a_stone_of_each_side_in_the_empty_corner_makes_it_contested() {
        DambadukBoards.Diagram d = DambadukBoards.parse(EXAMPLE_12_3);
        List<Stone> stones = new java.util.ArrayList<>(d.stones());
        stones.add(new Stone(50, 0, StoneKind.STRAIGHT, 4, 5));                               // E6 흑
        stones.add(new Stone(51, 1, StoneKind.STRAIGHT, 5, 6));                               // F7 백
        Territory t = TerritoryRule.compute(d.walls(), stones, 2);
        Region corner = t.regions().get(2);
        assertThat(corner.kind()).isEqualTo(RegionKind.CONTESTED);
        assertThat(corner.seats()).containsExactly(0, 1);
        assertThat(corner.owner()).isEqualTo(-1);
        assertThat(t.scores()).containsExactly(21, 16);                                       // 다툼 터는 아무의 점수도 아니다
        assertThat(t.hasContested()).isTrue();
    }

    @Test
    void the_largest_home_is_the_biggest_single_region_not_the_sum() {
        // 왼쪽 위 2×2(A1·B1·A2·B2)를 떼어 흑 돌 하나, 나머지 45칸에 흑·백 — 흑 집 4, 다툼 45
        Walls w = Walls.NONE
                .with(Grid.face(new com.mirboard.domain.game.dambaduk.board.Cell(1, 0),
                        com.mirboard.domain.game.dambaduk.board.Dir.RIGHT))
                .with(Grid.face(new com.mirboard.domain.game.dambaduk.board.Cell(1, 1),
                        com.mirboard.domain.game.dambaduk.board.Dir.RIGHT))
                .with(Grid.face(new com.mirboard.domain.game.dambaduk.board.Cell(0, 1),
                        com.mirboard.domain.game.dambaduk.board.Dir.DOWN))
                .with(Grid.face(new com.mirboard.domain.game.dambaduk.board.Cell(1, 1),
                        com.mirboard.domain.game.dambaduk.board.Dir.DOWN));
        Territory t = TerritoryRule.compute(w, List.of(
                new Stone(0, 0, StoneKind.BENT, 0, 0),
                new Stone(1, 0, StoneKind.STRAIGHT, 4, 4),
                new Stone(2, 1, StoneKind.STRAIGHT, 5, 5)), 2);
        assertThat(t.regions()).extracting(Region::size).containsExactly(4, 45);
        assertThat(t.scores()).containsExactly(4, 0);
        assertThat(t.largest()).containsExactly(4, 0);
    }

    @Test
    void two_homes_add_up_and_the_largest_is_one_of_them() {
        // 맨 윗줄(7칸)과 맨 아랫줄(7칸)을 떼어 둘 다 백 집, 가운데 35칸은 흑만
        Walls w = Walls.NONE;
        for (int x = 0; x < Grid.SIZE; x++) {
            w = w.with(Grid.face(new com.mirboard.domain.game.dambaduk.board.Cell(x, 0),
                    com.mirboard.domain.game.dambaduk.board.Dir.DOWN));
            w = w.with(Grid.face(new com.mirboard.domain.game.dambaduk.board.Cell(x, 5),
                    com.mirboard.domain.game.dambaduk.board.Dir.DOWN));
        }
        Territory t = TerritoryRule.compute(w, List.of(
                new Stone(0, 1, StoneKind.STRAIGHT, 0, 0),
                new Stone(1, 1, StoneKind.STRAIGHT, 6, 6),
                new Stone(2, 0, StoneKind.STRAIGHT, 3, 3)), 2);
        assertThat(t.regions()).extracting(Region::size).containsExactly(7, 35, 7);
        assertThat(t.scores()).containsExactly(35, 14);
        assertThat(t.largest()).containsExactly(35, 7);
    }

    @Test
    void all_84_walls_isolate_every_cell() {
        Walls w = Walls.NONE;
        for (int f = 0; f < Grid.FACES; f++) {
            w = w.with(f);
        }
        Territory t = TerritoryRule.compute(w, List.of(
                new Stone(0, 0, StoneKind.STRAIGHT, 0, 0), new Stone(1, 1, StoneKind.STRAIGHT, 1, 0),
                new Stone(2, 2, StoneKind.STRAIGHT, 2, 0)), 3);
        assertThat(t.regions()).hasSize(49);
        assertThat(t.regions()).extracting(Region::kind).filteredOn(k -> k == RegionKind.EMPTY).hasSize(46);
        assertThat(t.scores()).containsExactly(1, 1, 1);
        assertThat(t.hasContested()).isFalse();
    }
}
