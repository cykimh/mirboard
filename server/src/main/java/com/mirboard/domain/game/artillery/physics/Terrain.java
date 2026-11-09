package com.mirboard.domain.game.artillery.physics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 지형 비트 마스크 640×360 (`docs/rules-artillery.md` §1). 상태에 저장하지 않는 <b>파생값</b>이다 — 상태는 (시드, 구멍 목록)만 갖고,
 * 이 마스크는 맵 생성 결과에 구멍을 차례로 지워 만든다(D-220). 가변이므로 공유할 때는 {@link #copy()}.
 */
public final class Terrain {

    public static final int W = Fixed.WIDTH;
    public static final int H = Fixed.HEIGHT;

    private final long[] bits;

    public Terrain() {
        this.bits = new long[(W * H + 63) / 64];
    }

    private Terrain(long[] bits) {
        this.bits = bits;
    }

    public Terrain copy() {
        return new Terrain(bits.clone());
    }

    /** 흙이면 true. 전장 밖은 흙이 아니다(위는 열린 하늘, 옆·아래는 끝 — 판정은 부르는 쪽). */
    public boolean solid(int x, int y) {
        if (x < 0 || x >= W || y < 0 || y >= H) {
            return false;
        }
        int i = y * W + x;
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }

    public void set(int x, int y) {
        if (x < 0 || x >= W || y < 0 || y >= H) {
            return;
        }
        int i = y * W + x;
        bits[i >>> 6] |= 1L << (i & 63);
    }

    public void clear(int x, int y) {
        if (x < 0 || x >= W || y < 0 || y >= H) {
            return;
        }
        int i = y * W + x;
        bits[i >>> 6] &= ~(1L << (i & 63));
    }

    /** {@code (x − cx)² + (y − cy)² ≤ r²} 인 칸을 지운다(구멍, §6.2). */
    public void carve(int cx, int cy, int r) {
        long rr = (long) r * r;
        for (int y = Math.max(0, cy - r); y <= Math.min(H - 1, cy + r); y++) {
            long dy = y - cy;
            for (int x = Math.max(0, cx - r); x <= Math.min(W - 1, cx + r); x++) {
                long dx = x - cx;
                if (dx * dx + dy * dy <= rr) {
                    clear(x, y);
                }
            }
        }
    }

    /** 이 열에서 위에서부터 처음 만나는 흙의 y, 없으면 −1. */
    public int surface(int x) {
        for (int y = 0; y < H; y++) {
            if (solid(x, y)) {
                return y;
            }
        }
        return -1;
    }

    /**
     * 열 단위 런 길이 부호 — 열마다 흙 구간의 [시작, 끝] (양끝 포함) 을 위에서부터 이어 붙인 목록. 클라 전달용(resync 공개 뷰).
     */
    public List<int[]> columnRuns() {
        List<int[]> columns = new ArrayList<>(W);
        int[] buf = new int[H + 2];
        for (int x = 0; x < W; x++) {
            int n = 0;
            int start = -1;
            for (int y = 0; y < H; y++) {
                boolean s = solid(x, y);
                if (s && start < 0) {
                    start = y;
                } else if (!s && start >= 0) {
                    buf[n++] = start;
                    buf[n++] = y - 1;
                    start = -1;
                }
            }
            if (start >= 0) {
                buf[n++] = start;
                buf[n++] = H - 1;
            }
            columns.add(Arrays.copyOf(buf, n));
        }
        return columns;
    }

    public int solidCount() {
        int n = 0;
        for (long b : bits) {
            n += Long.bitCount(b);
        }
        return n;
    }

    /** 결정성 테스트용 해시(FNV-1a 64 over words). */
    public long hash() {
        long h = 0xcbf29ce484222325L;
        for (long b : bits) {
            h ^= b;
            h *= 0x100000001b3L;
        }
        return h;
    }
}
