package com.mirboard.domain.game.starsquare.puzzle;

/**
 * 완성 판정과 진행도(`docs/rules-starsquare.md` §5). 순수 함수만 있다 — 서버가 판을 받을 때마다 부른다.
 */
public final class MagicCheck {

    private MagicCheck() {
    }

    /** 마방진 합 M = N(N²+1)/2 — 3·4·5 → 15·34·65. */
    public static int magicSum(int n) {
        return n * (n * n + 1) / 2;
    }

    /** 판이 마방진인가 — 모든 칸이 1~N² 이고 서로 다르며 2N+2 줄의 합이 모두 M. */
    public static boolean isMagic(Grid grid) {
        int n = grid.n();
        int nn = n * n;
        long seen = 0;
        for (int i = 0; i < nn; i++) {
            int v = grid.get(i);
            if (v < 1 || v > nn || (seen & (1L << v)) != 0) {
                return false;
            }
            seen |= 1L << v;
        }
        Lines lines = Lines.of(n);
        int m = magicSum(n);
        for (int l = 0; l < lines.count(); l++) {
            int sum = 0;
            for (int cell : lines.cells(l)) {
                sum += grid.get(cell);
            }
            if (sum != m) {
                return false;
            }
        }
        return true;
    }

    /** 완성 = 마방진 그리고 단서 칸이 단서 값과 같다(§5). 정답과 같은지는 보지 않는다. */
    public static boolean isMagic(Grid grid, Grid clues) {
        return grid.n() == clues.n() && clues.nonZeroCellsMatch(grid) && isMagic(grid);
    }

    /**
     * 진행도(§5.1). 채운 칸 = 0 이 아니고 판에서 한 번만 쓰인 값이 든 칸, 맞은 줄 = 칸이 모두 채운 칸이고 합이 M 인 줄.
     */
    public static Progress progress(Grid grid) {
        int n = grid.n();
        int nn = n * n;
        int[] uses = new int[nn + 1];
        for (int i = 0; i < nn; i++) {
            uses[grid.get(i)]++;
        }
        boolean[] counted = new boolean[nn];
        int filled = 0;
        for (int i = 0; i < nn; i++) {
            int v = grid.get(i);
            if (v != 0 && uses[v] == 1) {
                counted[i] = true;
                filled++;
            }
        }
        Lines lines = Lines.of(n);
        int m = magicSum(n);
        int good = 0;
        for (int l = 0; l < lines.count(); l++) {
            int sum = 0;
            boolean all = true;
            for (int cell : lines.cells(l)) {
                if (!counted[cell]) {
                    all = false;
                    break;
                }
                sum += grid.get(cell);
            }
            if (all && sum == m) {
                good++;
            }
        }
        return new Progress(filled, good);
    }
}
