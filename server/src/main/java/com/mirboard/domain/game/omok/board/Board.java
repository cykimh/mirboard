package com.mirboard.domain.game.omok.board;

import java.util.Arrays;

/**
 * 15×15 판 (룰 §1). 불변 — {@link #with} 는 새 판을 돌려준다. 상태에는 수순만 저장하고 판은 수순에서 다시 만든다
 * ({@code OmokState.board()}).
 *
 * <p>금수 판정처럼 돌을 놓았다 거두는 계산은 {@link #cells()} 사본(가변 {@code byte[]})에서 한다 — 판을 수천 번 복사하지
 * 않으려고.
 */
public final class Board {

    public static final int SIZE = 15;
    public static final int CELLS = SIZE * SIZE;

    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;

    private final byte[] cells;
    private final int stoneCount;

    private Board(byte[] cells, int stoneCount) {
        this.cells = cells;
        this.stoneCount = stoneCount;
    }

    public static Board empty() {
        return new Board(new byte[CELLS], 0);
    }

    public static int index(int x, int y) {
        return y * SIZE + x;
    }

    public static byte code(Stone stone) {
        return stone == Stone.BLACK ? BLACK : WHITE;
    }

    /** 그 칸의 돌, 비었으면 null. 판 밖이면 {@link IllegalArgumentException}. */
    public Stone stoneAt(Point p) {
        if (!p.onBoard()) {
            throw new IllegalArgumentException("Off board: " + p);
        }
        return switch (cells[index(p.x(), p.y())]) {
            case BLACK -> Stone.BLACK;
            case WHITE -> Stone.WHITE;
            default -> null;
        };
    }

    public boolean isEmpty(Point p) {
        return cells[index(p.x(), p.y())] == EMPTY;
    }

    /** 빈칸 p 에 돌을 놓은 새 판. */
    public Board with(Point p, Stone stone) {
        if (!p.onBoard() || !isEmpty(p)) {
            throw new IllegalArgumentException("Cannot place at " + p);
        }
        byte[] next = cells.clone();
        next[index(p.x(), p.y())] = code(stone);
        return new Board(next, stoneCount + 1);
    }

    public int stoneCount() {
        return stoneCount;
    }

    public boolean isFull() {
        return stoneCount == CELLS;
    }

    /** 가변 사본 — 계산용. */
    public byte[] cells() {
        return cells.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Board b && Arrays.equals(cells, b.cells);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(cells);
    }

    /** 디버깅용 — {@code X} 흑, {@code O} 백, {@code .} 빈칸. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                sb.append(switch (cells[index(x, y)]) {
                    case BLACK -> 'X';
                    case WHITE -> 'O';
                    default -> '.';
                });
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
