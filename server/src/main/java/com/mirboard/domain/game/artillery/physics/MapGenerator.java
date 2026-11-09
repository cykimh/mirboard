package com.mirboard.domain.game.artillery.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * 결정적 맵 생성 (`docs/rules-artillery.md` §2). 같은 (시드, 인원)이면 같은 지형·같은 출발 자리다.
 *
 * <p>흙섬 2~4개를 좌우 대칭으로 놓는다 — 왼쪽 절반 섬을 x → 639 − x 로 비추고, 개수가 홀수면 그 자체가 대칭인 가운데 섬을 더한다.
 * 섬 하나는 노이즈를 얹은 윗면과 아래로 처진 그릇 모양 아랫면 사이를 칠한 것이다. 출발 자리는 전장을 인원 수로 나눈 띠마다
 * 띠 가운데에 가장 가까운 평평한 열이다. 어느 띠에도 자리가 없으면 시드를 다시 굴린다.
 */
public final class MapGenerator {

    static final int MAX_ATTEMPTS = 32;
    /** 출발 자리 아래로 이어져야 하는 흙 칸 수. */
    static final int MIN_GROUND = 12;
    private static final int HALF = Fixed.WIDTH / 2;

    public record Spawn(int x, int footY) {
    }

    /** @param spawns 띠 순서(왼쪽부터). 좌석 배정은 엔진이 섞는다. */
    public record GeneratedMap(Terrain terrain, List<Spawn> spawns) {
        public GeneratedMap {
            spawns = List.copyOf(spawns);
        }
    }

    private MapGenerator() {
    }

    public static GeneratedMap generate(long seed, int players) {
        if (players < 1 || players > 8) {
            throw new IllegalArgumentException("players out of range: " + players);
        }
        Terrain last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long s = attempt == 0 ? seed : SplitMix64.mix(seed, 9000 + attempt);
            Terrain terrain = islands(s);
            List<Spawn> spawns = spawnsByBand(terrain, players);
            if (spawns != null) {
                return new GeneratedMap(terrain, spawns);
            }
            last = terrain;
        }
        return new GeneratedMap(last, spawnsAnywhere(last, players));
    }

    // ---------- 섬 ----------

    private static Terrain islands(long seed) {
        SplitMix64.Stream rng = new SplitMix64.Stream(seed, 0);
        int k = 2 + rng.below(3);
        int left = k / 2;
        Terrain t = new Terrain();
        int hwMax = left == 1 ? 110 : 75;
        for (int i = 0; i < left; i++) {
            int cx = HALF * (2 * i + 1) / (2 * left) + rng.between(-20, 20);
            Island island = new Island(cx, rng.between(60, hwMax), rng.between(150, 250), rng.between(40, 80),
                    rng.between(0, 20), SplitMix64.mix(seed, 100 + i));
            for (int x = 0; x < HALF; x++) {
                island.paintColumn(t, x, x);
                island.paintColumn(t, Fixed.WIDTH - 1 - x, x);   // 거울
            }
        }
        if (k % 2 == 1) {
            Island center = new Island(HALF, rng.between(40, 80), rng.between(150, 250), rng.between(40, 80),
                    rng.between(0, 20), SplitMix64.mix(seed, 200));
            for (int x = 0; x < Fixed.WIDTH; x++) {
                // 가운데 섬은 거리 d 로만 모양을 정해 스스로 대칭이다(319 ↔ 320 이 같은 d).
                int d = x < HALF ? HALF - 1 - x : x - HALF;
                center.paintSymmetric(t, x, d);
            }
        }
        return t;
    }

    private record Island(int cx, int hw, int top, int depth, int bumpH, long noiseSeed) {

        /** 섬 좌표계의 열 {@code sx} 모양을 전장 열 {@code x} 에 칠한다. */
        void paintColumn(Terrain t, int x, int sx) {
            int dx = sx - cx;
            if (Math.abs(dx) > hw) {
                return;
            }
            fill(t, x, dx, sx + 64);
        }

        /** 가운데 섬 — 중심에서의 거리 d 로 칠한다. */
        void paintSymmetric(Terrain t, int x, int d) {
            if (d > hw) {
                return;
            }
            fill(t, x, d, d + 64);
        }

        private void fill(Terrain t, int x, int dx, int noiseX) {
            long hw2 = (long) hw * hw;
            long shape = hw2 - (long) dx * dx;          // 0 ~ hw²
            int bump = (int) (bumpH * shape / hw2);
            int noise = valueNoise(noiseSeed, noiseX, 32, 6) + valueNoise(noiseSeed ^ 0x5DEECE66DL, noiseX, 12, 2);
            int ty = top - bump + noise;
            int by = top + (int) (depth * shape / hw2);
            by = Math.max(by, ty + 4);
            for (int y = Math.max(0, ty); y <= Math.min(Fixed.HEIGHT - 1, by); y++) {
                t.set(x, y);
            }
        }
    }

    /** 정수 값 노이즈 — 격자 간격 {@code spacing} 마다 −amp~amp 값, 사이는 정수 smoothstep 보간. x ≥ 0. */
    static int valueNoise(long seed, int x, int spacing, int amp) {
        int i = x / spacing;
        int f = x % spacing;
        int a = SplitMix64.below(seed, 1000 + i, 2 * amp + 1) - amp;
        int b = SplitMix64.below(seed, 1001 + i, 2 * amp + 1) - amp;
        long t = (long) f * 1024 / spacing;
        long s = t * t * (3 * 1024 - 2 * t) / (1024L * 1024);
        return (int) (a + (b - a) * s / 1024);
    }

    // ---------- 출발 자리 ----------

    /** 띠마다 하나. 한 띠라도 없으면 null. */
    static List<Spawn> spawnsByBand(Terrain t, int n) {
        List<Spawn> spawns = new ArrayList<>(n);
        for (int band = 0; band < n; band++) {
            int from = band * Fixed.WIDTH / n;
            int to = (band + 1) * Fixed.WIDTH / n;
            int center = (2 * band + 1) * Fixed.WIDTH / (2 * n);
            Spawn best = null;
            int bestDist = Integer.MAX_VALUE;
            for (int x = from; x < to; x++) {
                if (!flat(t, x)) {
                    continue;
                }
                int dist = Math.abs(x - center);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new Spawn(x, t.surface(x) - 1);
                }
            }
            if (best == null) {
                return null;
            }
            spawns.add(best);
        }
        return spawns;
    }

    /** 마지막 수단 — 띠 제약 없이 평평한 열 중 고르게 n 개. */
    static List<Spawn> spawnsAnywhere(Terrain t, int n) {
        List<Integer> flatColumns = new ArrayList<>();
        for (int x = 0; x < Fixed.WIDTH; x++) {
            if (flat(t, x)) {
                flatColumns.add(x);
            }
        }
        if (flatColumns.size() < n) {
            throw new IllegalStateException("map has fewer flat columns than players: " + flatColumns.size());
        }
        List<Spawn> spawns = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int x = flatColumns.get((int) ((long) (2 * i + 1) * flatColumns.size() / (2L * n)));
            spawns.add(new Spawn(x, t.surface(x) - 1));
        }
        return spawns;
    }

    /** 평평함 (§2-3): x 16~623, 윗면이 20~339, 좌우 3칸 윗면 높이 차 ≤ 2, 윗면부터 아래로 흙 12칸. */
    static boolean flat(Terrain t, int x) {
        if (x < 16 || x > Fixed.WIDTH - 17) {
            return false;
        }
        int s = t.surface(x);
        if (s < 20 || s >= Fixed.HEIGHT - 20) {
            return false;
        }
        for (int d = -3; d <= 3; d++) {
            int o = t.surface(x + d);
            if (o < 0 || Math.abs(o - s) > 2) {
                return false;
            }
        }
        for (int y = s; y < s + MIN_GROUND; y++) {      // 얇은 판자 위는 피한다(한 발에 떨어진다)
            if (!t.solid(x, y)) {
                return false;
            }
        }
        return true;
    }
}
