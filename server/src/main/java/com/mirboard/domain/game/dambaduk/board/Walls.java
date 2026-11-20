package com.mirboard.domain.game.dambaduk.board;

import java.util.ArrayList;
import java.util.List;

/**
 * 세워진 담 집합 — 면 84개의 비트(0~63 은 {@code lo}, 64~83 은 {@code hi}). 불변. 저장·이벤트에는 {@link #toList()} 의 면 번호
 * 목록으로 나간다(상태 JSON 이 읽기 쉽게).
 */
public record Walls(long lo, long hi) {

    public static final Walls NONE = new Walls(0L, 0L);

    public static Walls of(List<Integer> faces) {
        Walls w = NONE;
        for (int f : faces) {
            w = w.with(f);
        }
        return w;
    }

    public boolean has(int face) {
        check(face);
        return face < 64 ? (lo >>> face & 1L) != 0 : (hi >>> (face - 64) & 1L) != 0;
    }

    public Walls with(int face) {
        check(face);
        return face < 64 ? new Walls(lo | 1L << face, hi) : new Walls(lo, hi | 1L << (face - 64));
    }

    public Walls without(int face) {
        check(face);
        return face < 64 ? new Walls(lo & ~(1L << face), hi) : new Walls(lo, hi & ~(1L << (face - 64)));
    }

    public int count() {
        return Long.bitCount(lo) + Long.bitCount(hi);
    }

    /** 칸 {@code c} 의 {@code d} 쪽이 막혔나 — 바깥담이거나 담. */
    public boolean blocked(Cell c, Dir d) {
        int f = Grid.face(c, d);
        return f < 0 || has(f);
    }

    /** 세워진 면 번호 오름차순. */
    public List<Integer> toList() {
        List<Integer> out = new ArrayList<>(count());
        for (int f = 0; f < Grid.FACES; f++) {
            if (has(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private static void check(int face) {
        if (face < 0 || face >= Grid.FACES) {
            throw new IllegalArgumentException("face " + face);
        }
    }
}
