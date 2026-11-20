package com.mirboard.domain.game.starsquare.puzzle;

/**
 * 줄의 기하(`docs/rules-starsquare.md` §1) — 가로 N + 세로 N + 대각선 2 = 2N+2 줄. 줄 번호는 가로 0..N−1, 세로 N..2N−1,
 * 왼쪽 위↘ 대각선 2N, 오른쪽 위↙ 대각선 2N+1.
 */
public final class Lines {

    private static final Lines[] BY_SIZE = new Lines[Grid.MAX_N + 1];

    static {
        for (int n = Grid.MIN_N; n <= Grid.MAX_N; n++) {
            BY_SIZE[n] = new Lines(n);
        }
    }

    private final int n;
    private final int[][] cellsOfLine;
    private final int[][] linesOfCell;

    private Lines(int n) {
        this.n = n;
        int count = 2 * n + 2;
        cellsOfLine = new int[count][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                cellsOfLine[i][j] = i * n + j;          // 가로 i
                cellsOfLine[n + i][j] = j * n + i;      // 세로 i
            }
            cellsOfLine[2 * n][i] = i * n + i;              // ↘
            cellsOfLine[2 * n + 1][i] = i * n + (n - 1 - i); // ↙
        }
        int[] counts = new int[n * n];
        for (int[] line : cellsOfLine) {
            for (int cell : line) {
                counts[cell]++;
            }
        }
        linesOfCell = new int[n * n][];
        for (int cell = 0; cell < n * n; cell++) {
            linesOfCell[cell] = new int[counts[cell]];
        }
        int[] fill = new int[n * n];
        for (int l = 0; l < count; l++) {
            for (int cell : cellsOfLine[l]) {
                linesOfCell[cell][fill[cell]++] = l;
            }
        }
    }

    public static Lines of(int n) {
        if (n < Grid.MIN_N || n > Grid.MAX_N) {
            throw new IllegalArgumentException("Unsupported size " + n);
        }
        return BY_SIZE[n];
    }

    public int n() {
        return n;
    }

    public int count() {
        return cellsOfLine.length;
    }

    /** 그 줄의 칸들(공유 배열 — 고치지 말 것). */
    public int[] cells(int line) {
        return cellsOfLine[line];
    }

    /** 그 칸이 든 줄들(공유 배열 — 고치지 말 것). */
    public int[] linesOf(int cell) {
        return linesOfCell[cell];
    }
}
