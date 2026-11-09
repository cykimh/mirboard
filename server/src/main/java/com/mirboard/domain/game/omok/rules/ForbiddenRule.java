package com.mirboard.domain.game.omok.rules;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 흑 금수 판정 (룰 §6). 정의는 렌주 하나이고 룰은 켜는 금수 집합만 다르다({@link OmokRule}).
 *
 * <p>판정 순서(룰 §6.2-7): 이기는 줄이면 금수 아님 → 장목 → 44 → 33. 3 은 "한 수 더 두면 열린 4 가 되고 그 한 수 자리가
 * 흑에게 금수가 아닌" 모양이라(룰 §6.3 거짓 33) 판정이 재귀다. 재귀는 같은 줄 몇 칸 안에서만 일어나고 깊이 상한
 * {@link #MAX_DEPTH} 를 넘으면 금수 아님으로 본다.
 *
 * <p>계산은 가변 사본 위에서 돌을 놓았다 거두며 한다 — 호출이 끝나면 사본은 원래대로다.
 */
public final class ForbiddenRule {

    /** 거짓 33 재귀 상한 (룰 §6.3, §11-6). */
    public static final int MAX_DEPTH = 8;

    private static final byte B = Board.BLACK;
    private static final byte E = Board.EMPTY;
    /** 돌 집합 비트마스크의 오프셋 기준 — 오프셋은 판 안에서 ±14 라 0..28 비트. */
    private static final int BIT_OFFSET = 14;

    private ForbiddenRule() {
    }

    /** 지금 판에서 흑이 빈칸 p 에 두면 금수인가 — 금수 종류, 아니면 empty. 백 차례에는 부르지 않는다(백에게는 금수가 없다). */
    public static Optional<ForbiddenKind> check(Board board, Point p, OmokRule rule) {
        if (!rule.hasForbiddenMoves() || !board.isEmpty(p)) {
            return Optional.empty();
        }
        byte[] cells = board.cells();
        if (!mayBeForbidden(cells, p.x(), p.y())) {
            return Optional.empty();
        }
        return Optional.ofNullable(kind(cells, p.x(), p.y(), rule, 0));
    }

    /** 지금 판의 흑 금수 자리 전부(y·x 순). 룰에 금수가 없으면 빈 목록. */
    public static List<ForbiddenPoint> all(Board board, OmokRule rule) {
        if (!rule.hasForbiddenMoves()) {
            return List.of();
        }
        byte[] cells = board.cells();
        List<ForbiddenPoint> out = new ArrayList<>();
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                if (Lines.at(cells, x, y) != E || !mayBeForbidden(cells, x, y)) {
                    continue;
                }
                ForbiddenKind k = kind(cells, x, y, rule, 0);
                if (k != null) {
                    out.add(new ForbiddenPoint(x, y, k));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 싸게 거르기: 33 은 두 방향에 흑이 각각 2개 이상(±4), 44(한 줄 44 포함)·장목은 한 방향에 4개 이상(±5)이 있어야 생긴다.
     * 둘 다 아니면 금수일 수 없다.
     */
    static boolean mayBeForbidden(byte[] cells, int x, int y) {
        int dirsWithTwo = 0;
        for (Direction d : Direction.values()) {
            int near = 0;
            int wide = 0;
            for (int k = -5; k <= 5; k++) {
                if (k == 0 || Lines.at(cells, x + k * d.dx, y + k * d.dy) != B) {
                    continue;
                }
                wide++;
                if (Math.abs(k) <= 4) {
                    near++;
                }
            }
            if (wide >= 4) {
                return true;
            }
            if (near >= 2) {
                dirsWithTwo++;
            }
        }
        return dirsWithTwo >= 2;
    }

    /** 빈칸 (x,y) 에 흑을 놓았을 때의 금수 종류, 아니면 null. cells 는 끝나면 원래대로. */
    static ForbiddenKind kind(byte[] cells, int x, int y, OmokRule rule, int depth) {
        Lines.set(cells, x, y, B);
        try {
            boolean overline = false;
            for (Direction d : Direction.values()) {
                int len = Lines.runLength(cells, x, y, d, B);
                if (isBlackWin(len, rule)) {
                    return null;                       // 5 가 금수보다 먼저 (룰 §5)
                }
                if (len >= 6) {
                    overline = true;
                }
            }
            if (overline && rule.forbidsOverline()) {
                return ForbiddenKind.OVERLINE;
            }
            int fours = 0;
            boolean[] hasFour = new boolean[4];
            for (Direction d : Direction.values()) {
                int f = countFours(cells, x, y, d, rule);
                fours += f;
                hasFour[d.ordinal()] = f > 0;
            }
            if (rule.forbidsDoubleFour() && fours >= 2) {
                return ForbiddenKind.DOUBLE_FOUR;
            }
            if (rule.forbidsDoubleThree()) {
                int threes = 0;
                for (Direction d : Direction.values()) {
                    if (!hasFour[d.ordinal()] && isThree(cells, x, y, d, rule, depth)) {
                        threes++;
                    }
                }
                if (threes >= 2) {
                    return ForbiddenKind.DOUBLE_THREE;
                }
            }
            return null;
        } finally {
            Lines.set(cells, x, y, E);
        }
    }

    private static boolean isBlackWin(int length, OmokRule rule) {
        return rule.blackExactFive() ? length == 5 : length >= 5;
    }

    /**
     * 놓인 흑 p 를 포함하는 d 방향의 4 개수 (룰 §6.2-2·3). 한 수 q 로 이기는 줄이 되는 자리마다 "그 줄의 흑 돌 집합"을
     * 모아 서로 다른 집합 수를 센다 — 열린 4 {@code _●●●●_} 는 두 자리가 같은 네 돌이라 1, 한 줄 44 {@code ●_●●●_●} 는 2.
     */
    static int countFours(byte[] cells, int x, int y, Direction d, OmokRule rule) {
        Set<Long> stoneSets = new HashSet<>();
        for (int k = -4; k <= 4; k++) {
            if (k == 0) {
                continue;
            }
            int qx = x + k * d.dx;
            int qy = y + k * d.dy;
            if (Lines.at(cells, qx, qy) != E) {
                continue;
            }
            Long mask = winningStoneSet(cells, qx, qy, k, d, rule);
            if (mask != null) {
                stoneSets.add(mask);
            }
        }
        return stoneSets.size();
    }

    /**
     * 빈칸 q(p 에서 오프셋 qk)에 흑을 두면 p 를 포함하는 이기는 줄이 되는가 — 되면 그 줄의 흑 돌(q 제외)을 p 기준 오프셋
     * 비트마스크(long)로, 아니면 null.
     */
    private static Long winningStoneSet(byte[] cells, int qx, int qy, int qk, Direction d, OmokRule rule) {
        Lines.set(cells, qx, qy, B);
        try {
            int[] r = Lines.run(cells, qx, qy, d, B);
            int len = r[1] - r[0] + 1;
            int start = qk + r[0];                  // p 기준 오프셋
            int end = qk + r[1];
            if (!isBlackWin(len, rule) || start > 0 || end < 0) {
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
            Lines.set(cells, qx, qy, E);
        }
    }

    /**
     * d 방향에 p 를 포함하는 진짜 3 이 있는가 (룰 §6.2-4, §6.3). 한 수 q 로 p·q 를 포함하는 열린 4 가 되고, q 가 흑에게
     * 금수가 아니면 3 이다. 깊이 상한을 넘으면 q 를 금수 아님으로 본다.
     */
    static boolean isThree(byte[] cells, int x, int y, Direction d, OmokRule rule, int depth) {
        for (int k = -4; k <= 4; k++) {
            if (k == 0) {
                continue;
            }
            int qx = x + k * d.dx;
            int qy = y + k * d.dy;
            if (Lines.at(cells, qx, qy) != E) {
                continue;
            }
            Lines.set(cells, qx, qy, B);
            boolean straight;
            try {
                straight = isStraightFour(cells, x, y, k, d, rule);
            } finally {
                Lines.set(cells, qx, qy, E);
            }
            if (!straight) {
                continue;
            }
            if (depth >= MAX_DEPTH || kind(cells, qx, qy, rule, depth + 1) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 놓인 p·q 를 둘 다 포함하는 열린 4 가 d 방향에 있는가 — 이기는 자리 두 곳이 같은 네 돌로 이기는 줄을 만든다.
     */
    private static boolean isStraightFour(byte[] cells, int x, int y, int qk, Direction d, OmokRule rule) {
        Set<Long> seen = new HashSet<>();
        for (int k = -5; k <= 5; k++) {
            if (k == 0 || k == qk) {
                continue;
            }
            int wx = x + k * d.dx;
            int wy = y + k * d.dy;
            if (Lines.at(cells, wx, wy) != E) {
                continue;
            }
            Long mask = winningStoneSet(cells, wx, wy, k, d, rule);
            if (mask == null || (mask & (1L << (qk + BIT_OFFSET))) == 0) {
                continue;
            }
            if (!seen.add(mask)) {
                return true;
            }
        }
        return false;
    }
}
