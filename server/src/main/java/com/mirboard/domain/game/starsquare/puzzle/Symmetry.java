package com.mirboard.domain.game.starsquare.puzzle;

/**
 * 마방진 성질을 보존하는 변환 16가지(`docs/rules-starsquare.md` §4.2) — 회전·반사 8 × 보수(x → N²+1−x) 2.
 *
 * <p>회전·반사는 가로·세로를 가로·세로로, 대각선을 대각선으로 옮기고, 보수는 줄 합 M 을 N(N²+1) − M = M 으로 옮긴다.
 * 빈 칸(0)은 보수에서도 0 이다 — 단서 판에도 같은 변환을 입힌다.
 *
 * @param index 0~15. {@code index % 8} 이 회전·반사, {@code index >= 8} 이면 보수
 */
public record Symmetry(int index) {

    public static final int COUNT = 16;

    public static final Symmetry IDENTITY = new Symmetry(0);

    public Symmetry {
        if (index < 0 || index >= COUNT) {
            throw new IllegalArgumentException("Symmetry index 0..15: " + index);
        }
    }

    public boolean complement() {
        return index >= 8;
    }

    public Grid apply(Grid grid) {
        int n = grid.n();
        int nn = n * n;
        int[] out = new int[nn];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int v = grid.get(r * n + c);
                if (complement() && v != 0) {
                    v = nn + 1 - v;
                }
                out[target(n, r, c)] = v;
            }
        }
        return Grid.of(out);
    }

    /** 칸 (r, c) 가 옮겨 가는 칸 번호. */
    private int target(int n, int r, int c) {
        int m = n - 1;
        return switch (index % 8) {
            case 0 -> r * n + c;              // 그대로
            case 1 -> c * n + (m - r);        // 90° 회전
            case 2 -> (m - r) * n + (m - c);  // 180°
            case 3 -> (m - c) * n + r;        // 270°
            case 4 -> r * n + (m - c);        // 좌우 반사
            case 5 -> (m - r) * n + c;        // 상하 반사
            case 6 -> c * n + r;              // ↘ 대각선 반사
            default -> (m - c) * n + (m - r); // ↙ 대각선 반사
        };
    }
}
