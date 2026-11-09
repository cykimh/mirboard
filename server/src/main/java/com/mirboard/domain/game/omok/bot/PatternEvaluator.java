package com.mirboard.domain.game.omok.bot;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.rules.Direction;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import com.mirboard.domain.game.omok.rules.Shapes;

/**
 * 한 수의 모양 점수 (D-179). 빈칸 p 에 color 를 놓아 보고 네 방향의 5·4·열린 3·닫힌 3·자리 잠재력을 센다. 정의는 금수 판정과
 * 같은 {@link Shapes} 를 쓴다.
 */
final class PatternEvaluator {

    static final int FIVE = 1_000_000;
    static final int WINNING = 100_000;      // 열린 4 또는 4 둘 — 다음 수에 반드시 5
    static final int FOUR_THREE = 50_000;
    static final int DOUBLE_THREE = 20_000;
    static final int FOUR = 3_000;
    static final int OPEN_THREE = 2_000;
    static final int CLOSED_THREE = 300;

    /** 한 수의 모양 요약. */
    record Summary(boolean five, int fours, boolean openFour, int openThrees, int closedThrees, int potential) {
        int score() {
            if (five) {
                return FIVE;
            }
            if (openFour || fours >= 2) {
                return WINNING + potential;
            }
            if (fours == 1 && openThrees >= 1) {
                return FOUR_THREE + potential;
            }
            if (openThrees >= 2) {
                return DOUBLE_THREE + potential;
            }
            return fours * FOUR + openThrees * OPEN_THREE + closedThrees * CLOSED_THREE + potential * 10;
        }
    }

    private PatternEvaluator() {
    }

    /** 빈칸 (x,y) 에 color 를 놓았을 때. cells 는 끝나면 원래대로. */
    static Summary analyze(byte[] cells, int x, int y, Stone color, OmokRule rule) {
        byte c = Board.code(color);
        cells[Board.index(x, y)] = c;
        try {
            boolean five = false;
            boolean openFour = false;
            int fours = 0;
            int openThrees = 0;
            int closedThrees = 0;
            int potential = 0;
            for (Direction d : Direction.values()) {
                Shapes.Line line = Shapes.line(cells, x, y, d, color, rule);
                if (line.five()) {
                    five = true;
                }
                fours += line.fours();
                openFour |= line.openFour();
                if (line.fours() == 0) {
                    if (hasRealCompletion(cells, x, y, d, color, rule)) {
                        openThrees++;
                    } else if (makesFourNext(cells, x, y, d, color, rule)) {
                        closedThrees++;
                    }
                }
                potential += windows(cells, x, y, d, c);
            }
            return new Summary(five, fours, openFour, openThrees, closedThrees, potential);
        } finally {
            cells[Board.index(x, y)] = Board.EMPTY;
        }
    }

    /**
     * 열린 3 인가 — 열린 4 완성 자리가 있고, 흑(금수 있는 룰)이면 그중 하나라도 금수가 아니어야 한다(거짓 3 을 진짜로 세지 않게).
     */
    private static boolean hasRealCompletion(byte[] cells, int x, int y, Direction d, Stone color, OmokRule rule) {
        int[] ks = Shapes.straightFourCompletions(cells, x, y, d, color, rule);
        if (ks.length == 0) {
            return false;
        }
        if (color != Stone.BLACK || !rule.hasForbiddenMoves()) {
            return true;
        }
        for (int k : ks) {
            if (ForbiddenRule.checkCells(cells, x + k * d.dx, y + k * d.dy, rule).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 한 수 더 두면 p 를 포함하는 4 가 되는가(닫힌 3). */
    private static boolean makesFourNext(byte[] cells, int x, int y, Direction d, Stone color, OmokRule rule) {
        byte c = Board.code(color);
        for (int k = -4; k <= 4; k++) {
            if (k == 0) {
                continue;
            }
            int qx = x + k * d.dx;
            int qy = y + k * d.dy;
            if (!inside(qx, qy) || cells[Board.index(qx, qy)] != Board.EMPTY) {
                continue;
            }
            cells[Board.index(qx, qy)] = c;
            try {
                if (Shapes.countFours(cells, x, y, d, color, rule) > 0) {
                    return true;
                }
            } finally {
                cells[Board.index(qx, qy)] = Board.EMPTY;
            }
        }
        return false;
    }

    /** p 를 품은 5칸 창 중 상대 돌·벽이 없는 창마다 내 돌 수의 제곱을 더한다 — 자리 잠재력. */
    private static int windows(byte[] cells, int x, int y, Direction d, byte c) {
        int sum = 0;
        for (int start = -4; start <= 0; start++) {
            int own = 0;
            boolean clean = true;
            for (int k = start; k < start + 5 && clean; k++) {
                int wx = x + k * d.dx;
                int wy = y + k * d.dy;
                if (!inside(wx, wy)) {
                    clean = false;
                } else {
                    byte v = cells[Board.index(wx, wy)];
                    if (v == c) {
                        own++;
                    } else if (v != Board.EMPTY) {
                        clean = false;
                    }
                }
            }
            if (clean) {
                sum += own * own;
            }
        }
        return sum;
    }

    static boolean inside(int x, int y) {
        return x >= 0 && x < Board.SIZE && y >= 0 && y < Board.SIZE;
    }
}
