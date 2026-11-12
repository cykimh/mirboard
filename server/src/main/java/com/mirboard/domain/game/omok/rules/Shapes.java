package com.mirboard.domain.game.omok.rules;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Stone;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 한 수가 만드는 모양 (룰 §6.2 의 정의를 색과 무관하게). 금수 판정({@link ForbiddenRule})과 봇 평가가 같은 정의를 쓴다.
 *
 * <p>모든 함수는 가변 사본({@code byte[]}) 위에서 돌을 놓았다 거두며, 끝나면 사본은 원래대로다. "4"는 한 수로 이기는 줄이
 * 되는 모양이고 이기는 자리마다 그 줄의 같은 색 돌 집합을 모아 서로 다른 집합 수로 센다 — 열린 4 의 두 자리는 같은 네 돌이라 1,
 * 한 줄 44 는 2.
 */
public final class Shapes {

    /** 돌 집합 비트마스크의 오프셋 기준 — 오프셋은 판 안에서 ±(SIZE−1) 라 0..2(SIZE−1) 비트(19줄이면 0..36, long 안). */
    private static final int BIT_OFFSET = Board.SIZE - 1;

    private Shapes() {
    }

    /** 한 방향의 모양 요약 — p 에 color 를 놓은 뒤. */
    public record Line(boolean five, boolean overline, int fours, boolean openFour) {
    }

    public static boolean isWin(int length, Stone color, OmokRule rule) {
        return WinRule.isWinningLength(length, color, rule);
    }

    static byte code(Stone color) {
        return Board.code(color);
    }

    /** 놓인 color 돌 p 를 지나는 d 방향의 5·장목·4 개수·열린 4. */
    public static Line line(byte[] cells, int x, int y, Direction d, Stone color, OmokRule rule) {
        byte c = code(color);
        int len = Lines.runLength(cells, x, y, d, c);
        boolean five = isWin(len, color, rule);
        Map<Long, Integer> sets = winningSets(cells, x, y, d, color, rule, Integer.MIN_VALUE);
        boolean open = sets.values().stream().anyMatch(n -> n >= 2);
        return new Line(five, len >= 6, sets.size(), open);
    }

    /**
     * 놓인 p 를 포함하는 d 방향의 이기는 자리들을 "그 줄의 돌 집합 → 자리 수"로. {@code mustInclude} 가 판 안 오프셋이면
     * 그 칸을 포함하는 집합만 센다(열린 4 판정에서 q 를 포함하는지 볼 때).
     */
    static Map<Long, Integer> winningSets(byte[] cells, int x, int y, Direction d, Stone color, OmokRule rule,
                                          int mustInclude) {
        Map<Long, Integer> sets = new HashMap<>();
        for (int k = -5; k <= 5; k++) {
            if (k == 0) {
                continue;
            }
            int wx = x + k * d.dx;
            int wy = y + k * d.dy;
            if (Lines.at(cells, wx, wy) != Board.EMPTY) {
                continue;
            }
            Long mask = winningStoneSet(cells, wx, wy, k, d, color, rule);
            if (mask == null) {
                continue;
            }
            if (mustInclude != Integer.MIN_VALUE && (mask & (1L << (mustInclude + BIT_OFFSET))) == 0) {
                continue;
            }
            sets.merge(mask, 1, Integer::sum);
        }
        return sets;
    }

    /** 놓인 p 를 포함하는 d 방향의 4 개수 — 이기는 자리의 서로 다른 돌 집합 수. */
    public static int countFours(byte[] cells, int x, int y, Direction d, Stone color, OmokRule rule) {
        Set<Long> sets = new HashSet<>();
        for (int k = -4; k <= 4; k++) {
            if (k == 0) {
                continue;
            }
            int qx = x + k * d.dx;
            int qy = y + k * d.dy;
            if (Lines.at(cells, qx, qy) != Board.EMPTY) {
                continue;
            }
            Long mask = winningStoneSet(cells, qx, qy, k, d, color, rule);
            if (mask != null) {
                sets.add(mask);
            }
        }
        return sets.size();
    }

    /**
     * 빈칸 q(p 에서 오프셋 qk)에 color 를 두면 p 를 포함하는 이기는 줄이 되는가 — 되면 그 줄의 돌(q 제외)을 p 기준 오프셋
     * 비트마스크로, 아니면 null.
     */
    static Long winningStoneSet(byte[] cells, int qx, int qy, int qk, Direction d, Stone color, OmokRule rule) {
        byte c = code(color);
        Lines.set(cells, qx, qy, c);
        try {
            int[] r = Lines.run(cells, qx, qy, d, c);
            int len = r[1] - r[0] + 1;
            int start = qk + r[0];
            int end = qk + r[1];
            if (!isWin(len, color, rule) || start > 0 || end < 0) {
                return null;
            }
            long mask = 0;
            for (int k = start; k <= end; k++) {
                if (k != qk) {
                    mask |= 1L << (k + BIT_OFFSET);
                }
            }
            return mask;
        } finally {
            Lines.set(cells, qx, qy, Board.EMPTY);
        }
    }

    /** 놓인 p·q(오프셋 qk) 를 둘 다 포함하는 열린 4 가 d 방향에 있는가. */
    static boolean isStraightFour(byte[] cells, int x, int y, int qk, Direction d, Stone color, OmokRule rule) {
        return winningSets(cells, x, y, d, color, rule, qk).values().stream().anyMatch(n -> n >= 2);
    }

    /**
     * d 방향에 p 를 포함하는 3 의 "열린 4 완성 자리"들(p 기준 오프셋). 금수 여부는 보지 않는다 — 금수 판정은 호출자가
     * ({@link ForbiddenRule} 은 거짓 33 재귀를, 봇은 그 자리를 둘 수 있는지를) 따로 본다.
     */
    public static int[] straightFourCompletions(byte[] cells, int x, int y, Direction d, Stone color, OmokRule rule) {
        byte c = code(color);
        int[] out = new int[8];
        int n = 0;
        for (int k = -4; k <= 4; k++) {
            if (k == 0) {
                continue;
            }
            int qx = x + k * d.dx;
            int qy = y + k * d.dy;
            if (Lines.at(cells, qx, qy) != Board.EMPTY) {
                continue;
            }
            Lines.set(cells, qx, qy, c);
            boolean straight;
            try {
                straight = isStraightFour(cells, x, y, k, d, color, rule);
            } finally {
                Lines.set(cells, qx, qy, Board.EMPTY);
            }
            if (straight) {
                out[n++] = k;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** 열린 4 완성 자리가 있는가 — 개수만 필요할 때 첫 자리에서 멈춘다(봇 평가용). */
    public static int straightFourCompletionCount(byte[] cells, int x, int y, Direction d, Stone color, OmokRule rule) {
        byte c = code(color);
        for (int k = -4; k <= 4; k++) {
            if (k == 0) {
                continue;
            }
            int qx = x + k * d.dx;
            int qy = y + k * d.dy;
            if (Lines.at(cells, qx, qy) != Board.EMPTY) {
                continue;
            }
            Lines.set(cells, qx, qy, c);
            try {
                if (isStraightFour(cells, x, y, k, d, color, rule)) {
                    return 1;
                }
            } finally {
                Lines.set(cells, qx, qy, Board.EMPTY);
            }
        }
        return 0;
    }

    /** 빈칸 (x,y) 에 color 를 두면 이기는 줄인가(네 방향 연속 길이만 — 싸다). cells 는 끝나면 원래대로. */
    public static boolean makesFive(byte[] cells, int x, int y, Stone color, OmokRule rule) {
        byte c = code(color);
        if (Lines.at(cells, x, y) != Board.EMPTY) {
            return false;
        }
        Lines.set(cells, x, y, c);
        try {
            for (Direction d : Direction.values()) {
                if (isWin(Lines.runLength(cells, x, y, d, c), color, rule)) {
                    return true;
                }
            }
            return false;
        } finally {
            Lines.set(cells, x, y, Board.EMPTY);
        }
    }
}
