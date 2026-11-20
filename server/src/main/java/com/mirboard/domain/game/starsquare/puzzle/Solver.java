package com.mirboard.domain.game.starsquare.puzzle;

import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * 백트래킹 마방진 풀이기(`docs/rules-starsquare.md` §4.3). <b>런타임 게임 경로에서는 쓰지 않는다</b> — 퍼즐 생성 도구·자원
 * 검증·테스트만 쓴다(§4.2 — 운영은 미리 만든 자원에서 꺼낸다).
 *
 * <p>고르는 순서: 빈 칸이 가장 적은 줄의 첫 빈 칸(빈 칸이 하나뿐이면 값이 M − 나머지 합으로 정해진다). 가지치기: 칸이 든 모든
 * 줄에서, 남은 빈 칸 k 개를 아직 안 쓴 값의 가장 작은 k 개·가장 큰 k 개로 채웠을 때 M 에 닿을 수 있어야 한다.
 */
public final class Solver {

    /** 셈 결과. {@code complete} 면 탐색을 다 했다(상한·노드 한도에 멈추지 않음). */
    public record Count(int solutions, boolean complete, long nodes, Grid first) {
    }

    private final int n;
    private final int nn;
    private final int m;
    private final Lines lines;
    private final int[] grid;
    private final int[] lineSum;
    private final int[] lineEmpty;
    private int used; // 비트 v = 값 v 를 씀
    private long nodes;
    private long nodeLimit;
    private boolean aborted;
    private int remainingCap; // 상한까지 남은 해 수
    private RandomGenerator shuffle;

    private Solver(Grid start) {
        this.n = start.n();
        this.nn = n * n;
        this.m = MagicCheck.magicSum(n);
        this.lines = Lines.of(n);
        this.grid = start.toArray();
        this.lineSum = new int[lines.count()];
        this.lineEmpty = new int[lines.count()];
        for (int l = 0; l < lines.count(); l++) {
            for (int cell : lines.cells(l)) {
                int v = grid[cell];
                if (v == 0) {
                    lineEmpty[l]++;
                } else {
                    lineSum[l] += v;
                }
            }
        }
        for (int v : grid) {
            if (v != 0) {
                if ((used & (1 << v)) != 0) {
                    throw new IllegalArgumentException("Duplicate value in start grid: " + v);
                }
                used |= 1 << v;
            }
        }
    }

    /**
     * 단서와 맞는 마방진을 {@code cap} 개까지 센다. 노드가 {@code nodeLimit} 을 넘으면 멈춘다({@code complete=false}).
     * 첫 해까지의 노드 수가 필요하면 {@code cap=1} 로 부른다(값은 오름차순으로 시도 — 결정적).
     */
    public static Count count(Grid clues, int cap, long nodeLimit) {
        Solver s = new Solver(clues);
        if (!s.startConsistent()) {
            return new Count(0, true, 0, null);
        }
        s.nodeLimit = nodeLimit;
        int[] found = {0};
        Grid[] first = {null};
        s.search(g -> {
            if (first[0] == null) {
                first[0] = g;
            }
            found[0]++;
        }, cap);
        boolean complete = !s.aborted && found[0] < cap;
        return new Count(found[0], complete, s.nodes, first[0]);
    }

    /** 빈 N×N 판의 마방진 전부(3×3 8개, 4×4 7,040개). 5×5 는 너무 많다 — 부르지 말 것. */
    public static void enumerate(int n, Consumer<Grid> sink) {
        if (n > 4) {
            throw new IllegalArgumentException("Enumeration only for 3x3 and 4x4");
        }
        Solver s = new Solver(Grid.empty(n));
        s.nodeLimit = Long.MAX_VALUE;
        s.search(sink, Integer.MAX_VALUE);
    }

    /**
     * 무작위 마방진 하나(값 순서를 섞어 탐색, 노드 한도마다 다시 시작). 생성 도구가 5×5 를 만들 때 쓴다.
     */
    public static Grid random(int n, RandomGenerator rng, long nodeLimitPerTry) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            Solver s = new Solver(Grid.empty(n));
            s.nodeLimit = nodeLimitPerTry;
            s.shuffle = rng;
            Grid[] out = {null};
            s.search(g -> out[0] = g, 1);
            if (out[0] != null) {
                return out[0];
            }
        }
        throw new IllegalStateException("No random magic square found");
    }

    private boolean startConsistent() {
        for (int l = 0; l < lines.count(); l++) {
            if (lineEmpty[l] == 0 && lineSum[l] != m) {
                return false;
            }
            if (lineEmpty[l] > 0 && !reachable(lineSum[l], lineEmpty[l], used)) {
                return false;
            }
        }
        return true;
    }

    /** @return true 면 그만(상한에 닿음·노드 한도) */
    private boolean search(Consumer<Grid> sink, int cap) {
        remainingCap = cap;
        return searchInner(sink);
    }

    private boolean searchInner(Consumer<Grid> sink) {
        if (++nodes > nodeLimit) {
            aborted = true;
            return true;
        }
        int best = -1;
        for (int l = 0; l < lineEmpty.length; l++) {
            int e = lineEmpty[l];
            if (e > 0 && (best < 0 || e < lineEmpty[best])) {
                best = l;
                if (e == 1) {
                    break;
                }
            }
        }
        if (best < 0) {
            sink.accept(Grid.of(grid));
            return --remainingCap <= 0;
        }
        int cell = -1;
        for (int c : lines.cells(best)) {
            if (grid[c] == 0) {
                cell = c;
                break;
            }
        }
        if (lineEmpty[best] == 1) {
            int v = m - lineSum[best];
            if (v < 1 || v > nn || (used & (1 << v)) != 0 || !fits(cell, v)) {
                return false;
            }
            place(cell, v);
            boolean stop = searchInner(sink);
            unplace(cell, v);
            return stop;
        }
        int[] candidates = new int[nn - Integer.bitCount(used)];
        int k = 0;
        for (int v = 1; v <= nn; v++) {
            if ((used & (1 << v)) == 0) {
                candidates[k++] = v;
            }
        }
        if (shuffle != null) {
            for (int i = candidates.length - 1; i > 0; i--) {
                int j = shuffle.nextInt(i + 1);
                int t = candidates[i];
                candidates[i] = candidates[j];
                candidates[j] = t;
            }
        }
        for (int v : candidates) {
            if (!fits(cell, v)) {
                continue;
            }
            place(cell, v);
            boolean stop = searchInner(sink);
            unplace(cell, v);
            if (stop) {
                return true;
            }
        }
        return false;
    }

    private boolean fits(int cell, int v) {
        int after = used | (1 << v);
        for (int l : lines.linesOf(cell)) {
            int s = lineSum[l] + v;
            int k = lineEmpty[l] - 1;
            if (k == 0) {
                if (s != m) {
                    return false;
                }
            } else if (!reachable(s, k, after)) {
                return false;
            }
        }
        return true;
    }

    /** 합 {@code s} 인 줄의 빈 칸 {@code k} 개를 안 쓴 값으로 채워 M 에 닿을 수 있나(가장 작은·큰 k 개 합의 범위). */
    private boolean reachable(int s, int k, int usedMask) {
        int need = m - s;
        if (need <= 0) {
            return false;
        }
        int min = 0;
        int taken = 0;
        for (int v = 1; v <= nn && taken < k; v++) {
            if ((usedMask & (1 << v)) == 0) {
                min += v;
                taken++;
            }
        }
        if (taken < k || min > need) {
            return false;
        }
        int max = 0;
        taken = 0;
        for (int v = nn; v >= 1 && taken < k; v--) {
            if ((usedMask & (1 << v)) == 0) {
                max += v;
                taken++;
            }
        }
        return max >= need;
    }

    private void place(int cell, int v) {
        grid[cell] = v;
        used |= 1 << v;
        for (int l : lines.linesOf(cell)) {
            lineSum[l] += v;
            lineEmpty[l]--;
        }
    }

    private void unplace(int cell, int v) {
        grid[cell] = 0;
        used &= ~(1 << v);
        for (int l : lines.linesOf(cell)) {
            lineSum[l] -= v;
            lineEmpty[l]++;
        }
    }
}
