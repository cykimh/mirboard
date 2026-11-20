package com.mirboard.domain.game.starsquare.puzzle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;

/**
 * N×N 판(`docs/rules-starsquare.md` §1). 칸 번호 {@code i = 행 × N + 열}, 값은 0(빈 칸) 또는 1~N². 불변이다.
 *
 * <p>JSON 은 정수 배열 하나다(길이 N²). 크기는 3·4·5 만 받는다(§4.1 — 7×7·6×6 없음).
 */
public final class Grid {

    public static final int MIN_N = 3;
    public static final int MAX_N = 5;

    private final int n;
    private final int[] cells;

    private Grid(int n, int[] cells) {
        this.n = n;
        this.cells = cells;
    }

    @JsonCreator
    public static Grid of(int... values) {
        int n = sideOf(values.length);
        if (n < 0) {
            throw new IllegalArgumentException("Grid length must be 9, 16 or 25: " + values.length);
        }
        int[] copy = values.clone();
        for (int v : copy) {
            if (v < 0 || v > n * n) {
                throw new IllegalArgumentException("Grid value out of range 0.." + n * n + ": " + v);
            }
        }
        return new Grid(n, copy);
    }

    /** 0 으로 가득한 N×N 판. */
    public static Grid empty(int n) {
        if (n < MIN_N || n > MAX_N) {
            throw new IllegalArgumentException("Unsupported size " + n);
        }
        return new Grid(n, new int[n * n]);
    }

    /** 길이가 3·4·5 의 제곱이면 그 변, 아니면 −1. */
    public static int sideOf(int length) {
        for (int n = MIN_N; n <= MAX_N; n++) {
            if (n * n == length) {
                return n;
            }
        }
        return -1;
    }

    public int n() {
        return n;
    }

    public int size() {
        return cells.length;
    }

    public int get(int cell) {
        return cells[cell];
    }

    public boolean isEmpty(int cell) {
        return cells[cell] == 0;
    }

    /** 이 칸만 바꾼 새 판. */
    public Grid with(int cell, int value) {
        if (value < 0 || value > cells.length) {
            throw new IllegalArgumentException("Grid value out of range: " + value);
        }
        int[] copy = cells.clone();
        copy[cell] = value;
        return new Grid(n, copy);
    }

    /** 0 이 아닌 칸의 수. */
    public int nonZeroCount() {
        int count = 0;
        for (int v : cells) {
            if (v != 0) {
                count++;
            }
        }
        return count;
    }

    /** 복사본 — 풀이기·도구가 고쳐 쓴다. */
    @JsonValue
    public int[] toArray() {
        return cells.clone();
    }

    public List<Integer> toList() {
        return Arrays.stream(cells).boxed().toList();
    }

    /**
     * 이 판의 0 이 아닌 칸이 {@code other} 의 같은 칸과 모두 같은가 — 단서 ⊂ 정답, 단서 일치 판정에 쓴다.
     */
    public boolean nonZeroCellsMatch(Grid other) {
        if (other.cells.length != cells.length) {
            return false;
        }
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0 && cells[i] != other.cells[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Grid g && Arrays.equals(cells, g.cells);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(cells);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < n; r++) {
            if (r > 0) {
                sb.append(" / ");
            }
            for (int c = 0; c < n; c++) {
                if (c > 0) {
                    sb.append(' ');
                }
                sb.append(cells[r * n + c]);
            }
        }
        return sb.toString();
    }
}
