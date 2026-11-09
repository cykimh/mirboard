package com.mirboard.domain.game.artillery.physics;

/**
 * 씨앗포의 유일한 난수원 (`docs/rules-artillery.md` §10). 스트림 번호 {@code k} 를 받는 무상태 함수라 같은 (시드, k) 면 늘 같은 값이다 —
 * JDK 난수 구현이 바뀌어도 맵·바람·봇 오차가 그대로다.
 *
 * <p>스트림 번호 구역: 맵 생성 0~(작은 수) · 맵 재시도 9000+ · 좌석 섞기 5000+ · 첫 차례 6000 · 바람 1000 + 차례 · 봇 오차 2,000,000 + 차례 × 8 + 좌석.
 */
public final class SplitMix64 {

    private SplitMix64() {
    }

    public static long mix(long seed, long k) {
        long z = seed + (k + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 0 ~ bound−1 (부호 없는 나머지). */
    public static int below(long seed, long k, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        return (int) Long.remainderUnsigned(mix(seed, k), bound);
    }

    /** 한 시드에서 스트림 번호를 차례로 올려 가며 뽑는 작은 커서 — 맵 생성처럼 여러 값을 순서대로 뽑을 때. */
    public static final class Stream {
        private final long seed;
        private long k;

        public Stream(long seed, long start) {
            this.seed = seed;
            this.k = start;
        }

        public int below(int bound) {
            return SplitMix64.below(seed, k++, bound);
        }

        /** lo ~ hi (양끝 포함). */
        public int between(int lo, int hi) {
            return lo + below(hi - lo + 1);
        }
    }
}
