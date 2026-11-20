package com.mirboard.domain.game.dambaduk.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 면 번호 (룰 §1) — 84면이 한 번씩, 양쪽 칸이 같은 번호, 가장자리는 −1. 번호는 클라와의 계약이다. */
class GridTest {

    @Test
    void every_inner_face_has_one_number_seen_from_both_sides() {
        Set<Integer> faces = new HashSet<>();
        int borders = 0;
        for (int idx = 0; idx < Grid.CELLS; idx++) {
            Cell c = Cell.ofIndex(idx);
            for (Dir d : Dir.values()) {
                int f = Grid.face(c, d);
                if (f < 0) {
                    borders++;
                    continue;
                }
                faces.add(f);
                assertThat(Grid.face(c.step(d), d.opposite())).isEqualTo(f);
                Cell[] pair = Grid.cellsOf(f);
                assertThat(List.of(pair)).contains(c, c.step(d));
            }
        }
        assertThat(faces).hasSize(84);
        assertThat(faces).allMatch(f -> f >= 0 && f < Grid.FACES);
        assertThat(borders).isEqualTo(28);                  // 바깥담 = 7 × 4
    }

    @Test
    void numbering_matches_the_documented_layout() {
        assertThat(Grid.face(new Cell(0, 0), Dir.RIGHT)).isZero();               // A1|B1
        assertThat(Grid.face(new Cell(5, 6), Dir.RIGHT)).isEqualTo(41);          // F7|G7
        assertThat(Grid.face(new Cell(0, 0), Dir.DOWN)).isEqualTo(42);           // A1/A2
        assertThat(Grid.face(new Cell(6, 6), Dir.UP)).isEqualTo(83);             // G6/G7
        assertThat(Grid.face(new Cell(0, 0), Dir.UP)).isEqualTo(-1);
        assertThat(Grid.face(new Cell(7, 0), Dir.LEFT)).isEqualTo(-1);           // 판 밖 칸
        assertThatThrownBy(() -> Grid.cellsOf(84)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void walls_set_and_clear_both_halves_of_the_bitset() {
        Walls w = Walls.NONE.with(0).with(63).with(64).with(83);
        assertThat(w.count()).isEqualTo(4);
        assertThat(w.toList()).containsExactly(0, 63, 64, 83);
        assertThat(w.without(63).without(83).toList()).containsExactly(0, 64);
        assertThat(Walls.of(w.toList())).isEqualTo(w);
        assertThat(w.blocked(new Cell(0, 0), Dir.UP)).isTrue();                  // 바깥담
        assertThat(w.blocked(new Cell(0, 0), Dir.RIGHT)).isTrue();               // 면 0
        assertThat(w.blocked(new Cell(1, 0), Dir.RIGHT)).isFalse();
    }
}
