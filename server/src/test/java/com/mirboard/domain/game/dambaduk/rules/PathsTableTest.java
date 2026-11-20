package com.mirboard.domain.game.dambaduk.rules;

import static com.mirboard.domain.game.dambaduk.DambadukBoards.c;
import static com.mirboard.domain.game.dambaduk.board.Dir.DOWN;
import static com.mirboard.domain.game.dambaduk.board.Dir.LEFT;
import static com.mirboard.domain.game.dambaduk.board.Dir.RIGHT;
import static com.mirboard.domain.game.dambaduk.board.Dir.UP;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.DambadukBoards;
import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 경로 전수 (룰 §4.1·§4.2·§15) — 빈 판 49칸 × 두 종류의 도착 칸 수 표, 경로 수 표, 룰 문서 §12 의 두 그림.
 */
class PathsTableTest {

    /** 곧은돌: 네 방향마다 min(2, 가장자리까지 칸 수). */
    private static final int[][] STRAIGHT_DESTINATIONS = {
            {4, 5, 6, 6, 6, 5, 4},
            {5, 6, 7, 7, 7, 6, 5},
            {6, 7, 8, 8, 8, 7, 6},
            {6, 7, 8, 8, 8, 7, 6},
            {6, 7, 8, 8, 8, 7, 6},
            {5, 6, 7, 7, 7, 6, 5},
            {4, 5, 6, 6, 6, 5, 4}};

    /** 꺾은돌: 이웃 + 대각. 모서리 3, 가장자리 5, 안쪽 8. */
    private static final int[][] BENT_DESTINATIONS = {
            {3, 5, 5, 5, 5, 5, 3},
            {5, 8, 8, 8, 8, 8, 5},
            {5, 8, 8, 8, 8, 8, 5},
            {5, 8, 8, 8, 8, 8, 5},
            {5, 8, 8, 8, 8, 8, 5},
            {5, 8, 8, 8, 8, 8, 5},
            {3, 5, 5, 5, 5, 5, 3}};

    /** 꺾은돌 경로 수 — 대각 칸은 길이 둘. 모서리 2 + 1×2, 가장자리 3 + 2×2, 안쪽 4 + 4×2. */
    private static final int[][] BENT_PATHS = {
            {4, 7, 7, 7, 7, 7, 4},
            {7, 12, 12, 12, 12, 12, 7},
            {7, 12, 12, 12, 12, 12, 7},
            {7, 12, 12, 12, 12, 12, 7},
            {7, 12, 12, 12, 12, 12, 7},
            {7, 12, 12, 12, 12, 12, 7},
            {4, 7, 7, 7, 7, 7, 4}};

    @Test
    void shapes_and_their_order() {
        assertThat(Paths.of(StoneKind.STRAIGHT)).containsExactly(
                List.of(UP), List.of(RIGHT), List.of(DOWN), List.of(LEFT),
                List.of(UP, UP), List.of(RIGHT, RIGHT), List.of(DOWN, DOWN), List.of(LEFT, LEFT));
        assertThat(Paths.of(StoneKind.BENT)).containsExactly(
                List.of(UP), List.of(RIGHT), List.of(DOWN), List.of(LEFT),
                List.of(UP, RIGHT), List.of(UP, LEFT), List.of(RIGHT, UP), List.of(RIGHT, DOWN),
                List.of(DOWN, RIGHT), List.of(DOWN, LEFT), List.of(LEFT, UP), List.of(LEFT, DOWN));
        assertThat(Paths.validShape(StoneKind.STRAIGHT, List.of(UP, LEFT))).isFalse();       // 곧은돌은 안 꺾는다
        assertThat(Paths.validShape(StoneKind.BENT, List.of(UP, UP))).isFalse();             // 꺾은돌은 곧게 2칸 불가
        assertThat(Paths.validShape(StoneKind.BENT, List.of(UP, DOWN))).isFalse();           // 되돌아오기(제자리) 불가
        assertThat(Paths.validShape(StoneKind.STRAIGHT, List.of(UP, DOWN))).isFalse();
        assertThat(Paths.validShape(StoneKind.STRAIGHT, List.of())).isFalse();
        assertThat(Paths.validShape(StoneKind.STRAIGHT, List.of(UP, UP, UP))).isFalse();
        assertThat(Paths.validShape(StoneKind.BENT, java.util.Arrays.asList(UP, null))).isFalse();
    }

    @Test
    void every_cell_of_the_empty_board_reaches_the_tabled_number_of_cells() {
        for (int y = 0; y < Grid.SIZE; y++) {
            for (int x = 0; x < Grid.SIZE; x++) {
                assertThat(destinations(StoneKind.STRAIGHT, x, y, Walls.NONE, List.of())).as("straight %d,%d", x, y)
                        .hasSize(STRAIGHT_DESTINATIONS[y][x]);
                assertThat(destinations(StoneKind.BENT, x, y, Walls.NONE, List.of())).as("bent %d,%d", x, y)
                        .hasSize(BENT_DESTINATIONS[y][x]);
                assertThat(options(StoneKind.BENT, x, y, Walls.NONE, List.of())).as("bent paths %d,%d", x, y)
                        .hasSize(BENT_PATHS[y][x]);
                assertThat(options(StoneKind.STRAIGHT, x, y, Walls.NONE, List.of())).as("straight paths %d,%d", x, y)
                        .hasSize(STRAIGHT_DESTINATIONS[y][x]);               // 곧은돌은 도착마다 길이 하나
            }
        }
    }

    @Test
    void the_free_sides_of_an_empty_board_destination_are_its_inner_faces() {
        // 모서리 A1 에서 곧은돌이 B1 로 — B1 의 위는 바깥담, 나머지 셋은 빈 면(들어온 면 LEFT 포함)
        MoveOption o = options(StoneKind.STRAIGHT, 0, 0, Walls.NONE, List.of()).stream()
                .filter(m -> m.path().equals(List.of(RIGHT))).findFirst().orElseThrow();
        assertThat(o.walls()).containsExactly(RIGHT, DOWN, LEFT);
    }

    @Test
    void rules_example_12_1_straight_stone() {
        DambadukBoards.Diagram d = DambadukBoards.parse(
                "      A   B   C   D   E   F   G",
                "    +---+---+---+---+---+---+---+",
                "  1 | .   .   .   .   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  2 | .   .   .   o   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  3 | .   .   .   *   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  4 | .   *   *   I | .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  5 | .   .   .   *   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  6 | .   .   .   *   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  7 | .   .   .   .   .   .   . |",
                "    +---+---+---+---+---+---+---+");
        assertThat(d.walls().count()).isEqualTo(1);
        Stone straight = d.stones().stream().filter(s -> s.seat() == 0).findFirst().orElseThrow();
        assertThat(destinationsOf(d, straight.id())).isEqualTo(labels(d.marks()));
        assertThat(labels(d.marks())).containsExactly("B4", "C4", "D3", "D5", "D6");
    }

    @Test
    void rules_example_12_2_bent_stone() {
        DambadukBoards.Diagram d = DambadukBoards.parse(
                "      A   B   C   D   E   F   G",
                "    +---+---+---+---+---+---+---+",
                "  1 | .   .   .   .   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  2 | .   .   .   .   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  3 | .   .   *   .   *   .   . |",
                "    +   +   +   +---+   +   +   +",
                "  4 | .   .   *   L   *   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  5 | .   .   *   *   o   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  6 | .   .   .   .   .   .   . |",
                "    +   +   +   +   +   +   +   +",
                "  7 | .   .   .   .   .   .   . |",
                "    +---+---+---+---+---+---+---+");
        Stone bent = d.stones().stream().filter(s -> s.kind() == StoneKind.BENT).findFirst().orElseThrow();
        assertThat(destinationsOf(d, bent.id())).isEqualTo(labels(d.marks()));
        assertThat(labels(d.marks())).containsExactly("C3", "C4", "C5", "D5", "E3", "E4");
        List<MoveOption> opts = MoveRules.options(d.stones(), 0, d.walls(), false, false).stream()
                .filter(o -> o.stoneId() == bent.id()).toList();
        assertThat(pathsTo(opts, "C3")).containsExactly(List.of(LEFT, UP));                  // [UP, LEFT] 은 담에 막힘
        assertThat(pathsTo(opts, "E3")).containsExactly(List.of(RIGHT, UP));
        assertThat(pathsTo(opts, "C5")).containsExactly(List.of(DOWN, LEFT), List.of(LEFT, DOWN));   // 길이 둘 다 열림
    }

    @Test
    void a_wall_closes_one_of_the_two_roads_to_a_diagonal_cell_but_not_the_cell() {
        Cell at = c("D4");
        Walls w = Walls.NONE.with(Grid.face(at, UP));                                         // D4 위
        List<MoveOption> opts = options(StoneKind.BENT, at.x(), at.y(), w, List.of());
        assertThat(pathsTo(opts, "E3")).containsExactly(List.of(RIGHT, UP));
        w = w.with(Grid.face(c("E4"), UP));                                                   // E4 위도 막으면 E3 은 못 간다
        assertThat(pathsTo(options(StoneKind.BENT, at.x(), at.y(), w, List.of()), "E3")).isEmpty();
    }

    @Test
    void a_jump_crosses_exactly_one_wall_and_frees_that_face() {
        Cell at = c("D4");
        Walls w = Walls.NONE.with(Grid.face(at, UP)).with(Grid.face(c("D3"), UP));            // D4 위, D3 위
        Stone s = new Stone(0, 0, StoneKind.STRAIGHT, at.x(), at.y());
        List<MoveOption> noJump = MoveRules.options(List.of(s), 0, w, false, false);
        List<MoveOption> jump = MoveRules.options(List.of(s), 0, w, true, false);
        assertThat(noJump).noneMatch(o -> o.path().getFirst() == UP);
        MoveOption d3 = jump.stream().filter(o -> o.path().equals(List.of(UP))).findFirst().orElseThrow();
        assertThat(d3.jump()).isTrue();
        assertThat(d3.jumpedFace()).isEqualTo(Grid.face(at, UP));
        assertThat(d3.walls()).contains(DOWN);                                                // 넘은 면에 다시 세울 수 있다
        assertThat(jump).noneMatch(o -> o.path().equals(List.of(UP, UP)));                    // 담 둘은 못 넘는다
    }

    private static List<MoveOption> options(StoneKind kind, int x, int y, Walls walls, List<Stone> others) {
        List<Stone> stones = new java.util.ArrayList<>(others);
        stones.add(new Stone(99, 0, kind, x, y));
        return MoveRules.options(stones, 0, walls, false, false).stream().filter(o -> o.stoneId() == 99).toList();
    }

    private static Set<String> destinations(StoneKind kind, int x, int y, Walls walls, List<Stone> others) {
        return options(kind, x, y, walls, others).stream().map(o -> new Cell(o.toX(), o.toY()).label())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> destinationsOf(DambadukBoards.Diagram d, int stoneId) {
        Stone s = d.stones().stream().filter(st -> st.id() == stoneId).findFirst().orElseThrow();
        return MoveRules.options(d.stones(), s.seat(), d.walls(), false, false).stream()
                .filter(o -> o.stoneId() == stoneId)
                .map(o -> new Cell(o.toX(), o.toY()).label()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> labels(List<Cell> cells) {
        return cells.stream().map(Cell::label).collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<List<Dir>> pathsTo(List<MoveOption> opts, String label) {
        Cell t = c(label);
        return opts.stream().filter(o -> o.toX() == t.x() && o.toY() == t.y()).map(MoveOption::path).toList();
    }
}
