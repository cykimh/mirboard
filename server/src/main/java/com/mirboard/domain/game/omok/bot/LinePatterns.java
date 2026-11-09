package com.mirboard.domain.game.omok.bot;

/**
 * 한 줄 모양 표 (D-191) — 어려움 탐색이 칸마다 네 방향 모양을 표 한 번 찾기로 얻게 한다.
 *
 * <p>빈칸 p 에 내 돌을 놓았다고 보고, p 를 가운데로 한 줄의 앞뒤 5칸(10칸)을 빈칸 0 · 내 돌 1 · 막힘(상대 돌·판 밖) 2 의 3진수로
 * 읽어 모양을 미리 계산해 둔다(3^10 = 59,049칸, 흑 렌주의 "정확히 5" 표와 "5 이상" 표 둘). 모양은 p 를 지나는 5 를 기준으로
 * 정의한다 — 5 · 장목 · 열린 4(한 수로 5 가 되는 자리가 둘 이상) · 4(하나) · 열린 3(한 수 더 두면 열린 4) · 3(한 수 더 두면 4) ·
 * 열린 2 · 2. 금수(거짓 3 등)는 보지 않는다 — 실제로 둘 수 있는지는 {@link com.mirboard.domain.game.omok.rules.ForbiddenRule} 이
 * 정한다. 표는 근사치 평가용이고 판정에 쓰지 않는다.
 */
final class LinePatterns {

    static final byte NONE = 0;
    static final byte TWO = 1;
    static final byte OPEN_TWO = 2;
    static final byte THREE = 3;
    static final byte OPEN_THREE = 4;
    static final byte FOUR = 5;
    static final byte OPEN_FOUR = 6;
    static final byte FIVE = 7;
    static final byte OVERLINE = 8;

    /** 표 칸의 위치 i(0..9)가 가리키는 p 기준 오프셋. */
    static final int[] OFFSETS = {-5, -4, -3, -2, -1, 1, 2, 3, 4, 5};
    static final int[] POW3 = new int[10];
    static final int SIZE;

    static {
        int v = 1;
        for (int i = 0; i < 10; i++) {
            POW3[i] = v;
            v *= 3;
        }
        SIZE = v;
    }

    /** "5 이상이면 이김" 표(백, 렌주 밖의 흑). */
    static final byte[] AT_LEAST_FIVE = build(false);
    /** "정확히 5" 표(렌주 흑) — 6 이상은 {@link #OVERLINE}. */
    static final byte[] EXACT_FIVE = build(true);

    private LinePatterns() {
    }

    private static byte[] build(boolean exact) {
        byte[] five = new byte[SIZE];   // 0 없음, 1 5, 2 장목
        byte[] wins = new byte[SIZE];   // 한 수로 5 가 되는 자리 수(2 에서 자름)
        int[] line = new int[11];
        for (int idx = 0; idx < SIZE; idx++) {
            decode(idx, line);
            int len = run(line);
            five[idx] = (byte) (isWin(len, exact) ? 1 : exact && len >= 6 ? 2 : 0);
            int n = 0;
            for (int i = 1; i <= 9; i++) {            // 오프셋 -4..4
                if (i == 5 || line[i] != 0) {
                    continue;
                }
                line[i] = 1;
                if (isWin(run(line), exact)) {
                    n++;
                }
                line[i] = 0;
            }
            wins[idx] = (byte) Math.min(n, 2);
        }
        byte[] three = new byte[SIZE];  // 2 열린 3, 1 3 — 이미 4 이상이면 0
        for (int idx = 0; idx < SIZE; idx++) {
            if (five[idx] != 0 || wins[idx] > 0) {
                continue;
            }
            int best = 0;
            for (int i = 1; i <= 8; i++) {            // 표 위치 1..8 = 오프셋 -4..4
                int j = afterOwn(idx, i);
                if (j < 0) {
                    continue;
                }
                best = Math.max(best, wins[j]);
            }
            three[idx] = (byte) best;
        }
        byte[] out = new byte[SIZE];
        for (int idx = 0; idx < SIZE; idx++) {
            byte t;
            if (five[idx] == 1) {
                t = FIVE;
            } else if (five[idx] == 2) {
                t = OVERLINE;
            } else if (wins[idx] >= 2) {
                t = OPEN_FOUR;
            } else if (wins[idx] == 1) {
                t = FOUR;
            } else if (three[idx] == 2) {
                t = OPEN_THREE;
            } else if (three[idx] == 1) {
                t = THREE;
            } else {
                int best = 0;
                for (int i = 1; i <= 8; i++) {
                    int j = afterOwn(idx, i);
                    if (j >= 0 && five[j] == 0) {
                        best = Math.max(best, three[j]);
                    }
                }
                t = best == 2 ? OPEN_TWO : best == 1 ? TWO : NONE;
            }
            out[idx] = t;
        }
        return out;
    }

    /** 표 위치 i 가 빈칸이면 그 칸에 내 돌을 둔 표 번호, 아니면 -1. */
    private static int afterOwn(int idx, int i) {
        int digit = idx / POW3[i] % 3;
        return digit == 0 ? idx + POW3[i] : -1;
    }

    /** 표 번호 → 줄(11칸, 가운데 5 = 내 돌). */
    private static void decode(int idx, int[] line) {
        int v = idx;
        for (int i = 0; i < 10; i++) {
            line[i < 5 ? i : i + 1] = v % 3;
            v /= 3;
        }
        line[5] = 1;
    }

    /** 가운데를 지나는 내 돌 연속 길이. */
    private static int run(int[] line) {
        int len = 1;
        for (int i = 4; i >= 0 && line[i] == 1; i--) {
            len++;
        }
        for (int i = 6; i <= 10 && line[i] == 1; i++) {
            len++;
        }
        return len;
    }

    private static boolean isWin(int len, boolean exact) {
        return exact ? len == 5 : len >= 5;
    }
}
