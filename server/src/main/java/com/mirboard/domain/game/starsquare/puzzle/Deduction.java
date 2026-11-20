package com.mirboard.domain.game.starsquare.puzzle;

/**
 * "줄 채우기" 추론(`docs/rules-starsquare.md` §4.3) — 빈 칸이 하나만 남은 줄은 그 칸이 M − 나머지 합이다. 이것만 되풀이해서
 * 채워지는 칸을 센다. 사람이 막히지 않고 이어 풀 수 있는 정도를 재는 자(생성 도구·자원 검증 전용).
 */
public final class Deduction {

    private Deduction() {
    }

    /** 줄 채우기만으로 채워지는 빈 칸의 수. 모순(범위 밖·중복)이 나오면 거기서 멈춘다. */
    public static int fillable(Grid clues) {
        int n = clues.n();
        int nn = n * n;
        int m = MagicCheck.magicSum(n);
        Lines lines = Lines.of(n);
        int[] g = clues.toArray();
        boolean[] used = new boolean[nn + 1];
        for (int v : g) {
            used[v] = true;
        }
        int filled = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int l = 0; l < lines.count(); l++) {
                int empty = -1;
                int blanks = 0;
                int sum = 0;
                for (int cell : lines.cells(l)) {
                    if (g[cell] == 0) {
                        blanks++;
                        empty = cell;
                    } else {
                        sum += g[cell];
                    }
                }
                if (blanks != 1) {
                    continue;
                }
                int v = m - sum;
                if (v < 1 || v > nn || used[v]) {
                    return filled;
                }
                g[empty] = v;
                used[v] = true;
                filled++;
                progress = true;
            }
        }
        return filled;
    }

    /** 빈 칸 중 줄 채우기로 채워지는 비율(빈 칸이 없으면 1). */
    public static double ratio(Grid clues) {
        int blanks = clues.size() - clues.nonZeroCount();
        return blanks == 0 ? 1.0 : (double) fillable(clues) / blanks;
    }
}
