package com.mirboard.domain.game.starsquare.puzzle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-starsquare.md` §15 — 3×3 의 9! = 362,880 배열 전부에서 {@code isMagic} 이 참인 것은 정확히 8개이고, 풀이기가 센 8개와
 * 같다. 판정 함수와 풀이기를 서로 다른 방식(전수 대 탐색)으로 대조한다.
 */
class MagicCheckExhaustiveTest {

    @Test
    void exactly_eight_of_all_9_factorial_arrangements_are_magic() {
        int[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        Set<Grid> magic = new HashSet<>();
        int[] count = {0};
        permute(values, 0, g -> {
            count[0]++;
            if (MagicCheck.isMagic(g)) {
                magic.add(g);
            }
        });
        assertThat(count[0]).isEqualTo(362_880);
        assertThat(magic).hasSize(8);

        Set<Grid> solved = new HashSet<>();
        Solver.enumerate(3, solved::add);
        assertThat(solved).isEqualTo(magic);
        // 3×3 마방진의 가운데는 늘 5
        assertThat(magic).allMatch(g -> g.get(4) == 5);
    }

    @Test
    void progress_completes_exactly_on_magic_arrangements() {
        int[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        permute(values, 0, g -> assertThat(MagicCheck.progress(g).complete(3)).isEqualTo(MagicCheck.isMagic(g)));
    }

    private interface Sink {
        void accept(Grid g);
    }

    private static void permute(int[] a, int k, Sink sink) {
        if (k == a.length) {
            sink.accept(Grid.of(a));
            return;
        }
        for (int i = k; i < a.length; i++) {
            swap(a, k, i);
            permute(a, k + 1, sink);
            swap(a, k, i);
        }
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
