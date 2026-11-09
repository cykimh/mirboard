package com.mirboard.domain.game.omok.bot;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 어려움 봇의 탐색 (D-191). 판을 바꿀 때 바뀐 줄의 칸만 다시 계산하는 칸 점수({@link LinePatterns} 표) 위에서
 * <ol>
 *   <li>내 5 · 상대 5 막기,
 *   <li>내 VCF(연속 4) · VCT(4 와 열린 3 을 섞어 몰아 이기기 — 상대의 막기와 역공 4 를 전부 응수로 본다),
 *   <li>그 밖은 반복 심화 알파베타(상대 위협이 있으면 막는 수·내 4 만, 상대 5 응수는 깊이를 쓰지 않음),
 *   <li>고른 수를 두고 상대에게 VCT 가 생기면 다음 후보로(안전 확인)
 * </ol>
 * 를 한다. 무작위·시간을 쓰지 않는다 — 계산 상한은 노드 수({@link Params#budget()})이고 순서는 점수 → 칸 번호라 같은 국면이면 같은
 * 수다. 노드는 돌 놓기 1번과 금수 판정 1번을 각각 1로 센다.
 */
final class HardSearch {

    static final int S_FIVE = 10_000_000;
    /** 열린 4 또는 44 를 만드는 자리 — 상대에게 5 가 없으면 다음 수에 이긴다. */
    static final int S_WIN = 1_000_000;
    static final int S_FOUR_THREE = 300_000;
    static final int S_DOUBLE_THREE = 80_000;

    static final int WIN = 1_000_000_000;
    private static final int MAX_PLY = 40;

    private static final int N = Board.SIZE;
    private static final int CELLS = Board.CELLS;
    private static final int[] DX = {1, 0, 1, 1};
    private static final int[] DY = {0, 1, 1, -1};
    private static final long[][] ZOBRIST = zobrist();

    /** 탐색 크기 — 테스트·조정용으로 한 곳에 둔다. */
    record Params(int budget, int vcfDepth, int vctDepth, int vctWidth, int vctShare, int rootWidth, int width,
                  int maxDepth, int safetyDepth, int safetyMoves, int safetyShare) {
    }

    /**
     * 운영 값 — 노드 5만 · VCF 깊이 14 · VCT 깊이 4(폭 10, 1.5만) · 루트 12 · 안쪽 8 · 최대 깊이 8 · 안전 확인(상대 VCT 깊이 2,
     * 6수, 1만). 이 기계 국면 1,000개 p99 36.7ms(D-191).
     */
    static final Params DEFAULT = new Params(50_000, 14, 4, 10, 15_000, 12, 8, 8, 2, 6, 10_000);

    final byte[] cells;
    final OmokRule rule;
    final int me;
    final Set<Point> legal;
    final Params params;
    /** 색 코드(1 흑, 2 백) → 칸 → 점수. 빈칸이 아니면 0. */
    final int[][] score = new int[3][CELLS];
    final byte[][][] type = new byte[3][4][CELLS];
    /** 색 → 방향 → 칸 → {@link LinePatterns} 표 번호. 돌을 놓고 거둘 때 그 줄의 칸만 한 자리씩 고친다. */
    private final int[][][] lineIndex = new int[3][4][CELLS];
    /** 색별 5 자리 수 · 위협(33 이상) 자리 수 — 판 전체를 훑지 않고 없음을 안다. */
    private final int[] fiveCount = new int[3];
    private final int[] threatCount = new int[3];
    private final byte[] blackTable;
    private final int[][] comboKey = new int[3][CELLS];
    private final int[] blackCombo;
    private final boolean[] blackMaybe = new boolean[COMBOS];
    /** 흑에게 금수일 수도 있는 칸 — 그런 칸만 {@link ForbiddenRule} 로 확인한다. */
    final boolean[] maybeForbidden = new boolean[CELLS];
    /** 거리 2 안 돌 수 — 0 보다 크면 후보. */
    final int[] near = new int[CELLS];
    long hash;
    /** 돌이 있는 범위 ±2(후보가 있을 수 있는 칸) — 후보 훑기를 이 안에서만. 놓을 때 넓히고 거둘 때 되돌린다. */
    private int boxX0 = N;
    private int boxX1 = -1;
    private int boxY0 = N;
    private int boxY1 = -1;
    private final int[] boxStack = new int[4 * (CELLS + 1)];
    private int boxTop;
    int nodes;
    int limit;

    private final Map<Long, Integer> vcfFail = new HashMap<>();
    private final Map<Long, Integer> vctFail = new HashMap<>();
    private final Map<Long, int[]> table = new HashMap<>();

    HardSearch(OmokBotView view, Set<Point> legal, Params params) {
        this.cells = view.board().cells();
        this.rule = view.rule();
        this.me = Board.code(view.me());
        this.legal = legal;
        this.params = params;
        this.limit = params.budget();
        this.blackTable = rule.blackExactFive() ? LinePatterns.EXACT_FIVE : LinePatterns.AT_LEAST_FIVE;
        this.blackCombo = comboScores(rule, Board.BLACK, blackMaybe);
        for (int q = 0; q < CELLS; q++) {
            if (cells[q] != Board.EMPTY) {
                hash ^= ZOBRIST[cells[q]][q];
                addNear(q, 1);
                growBox(q);
            }
            for (int d = 0; d < 4; d++) {
                lineIndex[1][d][q] = initialIndex(q % N, q / N, d, 1);
                lineIndex[2][d][q] = initialIndex(q % N, q / N, d, 2);
            }
        }
        for (int q = 0; q < CELLS; q++) {
            if (cells[q] == Board.EMPTY) {
                for (int d = 0; d < 4; d++) {
                    type[1][d][q] = blackTable[lineIndex[1][d][q]];
                    type[2][d][q] = LinePatterns.AT_LEAST_FIVE[lineIndex[2][d][q]];
                }
                combine(1, q);
                combine(2, q);
            }
        }
    }

    boolean exhausted() {
        return nodes >= limit;
    }

    // ---------------------------------------------------------------- 결정

    Point choose() {
        int opp = 3 - me;
        int five = anyFive(me);
        if (five >= 0) {
            return pointOf(five);
        }
        int block = anyFive(opp);
        if (block >= 0 && legal.contains(pointOf(block))) {
            return pointOf(block);
        }
        if (block < 0) {
            int vcf = withLimit(params.vctShare(), () -> vcf(me, params.vcfDepth()));
            if (vcf >= 0 && legal.contains(pointOf(vcf))) {
                return pointOf(vcf);
            }
            int vct = withLimit(params.vctShare(), () -> vct(me, params.vctDepth()));
            if (vct >= 0 && legal.contains(pointOf(vct))) {
                return pointOf(vct);
            }
        }
        int[] order = withLimitArr(params.budget() - params.safetyShare(), this::rootSearch);
        if (order.length == 0) {
            return null;
        }
        // 안전 확인 — 앞 순서부터, 두고 나서 상대에게 VCT 가 없는 첫 수.
        int checks = Math.min(order.length, params.safetyMoves());
        for (int i = 0; i < checks && !exhausted(); i++) {
            int m = order[i];
            make(m, me);
            int threat = vct(opp, params.safetyDepth());
            undo(m);
            if (threat < 0 && !exhausted()) {
                return pointOf(m);
            }
        }
        return pointOf(order[0]);
    }

    private int withLimit(int share, java.util.function.IntSupplier phase) {
        int saved = limit;
        limit = Math.min(saved, nodes + share);
        try {
            return exhausted() ? -1 : phase.getAsInt();
        } finally {
            limit = saved;
        }
    }

    private int[] withLimitArr(int cap, java.util.function.Supplier<int[]> phase) {
        int saved = limit;
        limit = Math.min(saved, cap);
        try {
            return phase.get();
        } finally {
            limit = saved;
        }
    }

    // ---------------------------------------------------------------- 알파베타

    /** 루트 후보를 반복 심화로 정렬해 돌려준다 — 끝까지 마친 마지막 깊이의 순서. */
    private int[] rootSearch() {
        int[] moves = genMoves(me, params.rootWidth(), true);
        if (moves.length <= 1) {
            return moves;
        }
        int[] order = moves.clone();
        for (int depth = 1; depth <= params.maxDepth(); depth++) {
            int[] values = new int[order.length];
            int alpha = -WIN - 1;
            int[] cur = order.clone();
            boolean done = true;
            for (int i = 0; i < cur.length; i++) {
                int m = cur[i];
                make(m, me);
                // 첫 수는 전체 창, 나머지는 첫 수보다 나은지만(영창 → 나으면 다시) 본다.
                int v;
                if (i == 0) {
                    v = -search(3 - me, depth - 1, -WIN - 1, WIN + 1, 1);
                } else {
                    v = -search(3 - me, depth - 1, -alpha - 1, -alpha, 1);
                    if (v > alpha && !exhausted()) {
                        v = -search(3 - me, depth - 1, -WIN - 1, -alpha, 1);
                    }
                }
                undo(m);
                if (exhausted()) {
                    done = false;
                    break;
                }
                values[i] = v;
                if (v > alpha) {
                    alpha = v;
                }
            }
            if (!done) {
                break;
            }
            order = sortByValue(cur, values);
            if (alpha >= WIN - MAX_PLY) {
                break;                                      // 이기는 수순을 찾았다
            }
        }
        return order;
    }

    private static int[] sortByValue(int[] moves, int[] values) {
        Integer[] idx = new Integer[moves.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        // 값 내림차순, 같으면 이전 순서(안정 정렬).
        Arrays.sort(idx, (a, b) -> Integer.compare(values[b], values[a]));
        int[] out = new int[moves.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = moves[idx[i]];
        }
        return out;
    }

    private int search(int side, int depth, int alpha, int beta, int ply) {
        if (exhausted()) {
            return 0;
        }
        int opp = 3 - side;
        if (anyFive(side) >= 0) {
            return WIN - ply;
        }
        int[] fives = fivePoints(opp);
        if (fives.length >= 2) {
            return -(WIN - ply - 1);
        }
        if (fives.length == 1) {
            int b = fives[0];
            if (!canPlay(b, side)) {
                return -(WIN - ply - 1);
            }
            if (ply >= MAX_PLY) {
                return evaluate(side);
            }
            make(b, side);
            int v = -search(opp, depth, -beta, -alpha, ply + 1);
            undo(b);
            return v;
        }
        if (depth <= 0 || ply >= MAX_PLY) {
            return evaluate(side);
        }
        long key = hash ^ (side == 1 ? 0 : 0x9E3779B97F4A7C15L);
        int[] entry = table.get(key);
        int ttMove = -1;
        if (entry != null) {
            ttMove = entry[3];
            if (entry[0] >= depth) {
                int v = entry[1];
                int bound = entry[2];
                if (bound == 0 || (bound == 1 && v >= beta) || (bound == -1 && v <= alpha)) {
                    return v;
                }
            }
        }
        int[] moves = genMoves(side, params.width(), false);
        if (moves.length == 0) {
            return 0;
        }
        if (ttMove >= 0) {
            for (int i = 0; i < moves.length; i++) {
                if (moves[i] == ttMove) {
                    System.arraycopy(moves, 0, moves, 1, i);
                    moves[0] = ttMove;
                    break;
                }
            }
        }
        int a0 = alpha;
        int best = -WIN - 1;
        int bestMove = moves[0];
        for (int m : moves) {
            make(m, side);
            int v = -search(opp, depth - 1, -beta, -alpha, ply + 1);
            undo(m);
            if (exhausted()) {
                return 0;
            }
            if (v > best) {
                best = v;
                bestMove = m;
            }
            if (v > alpha) {
                alpha = v;
            }
            if (alpha >= beta) {
                break;
            }
        }
        int bound = best <= a0 ? -1 : best >= beta ? 1 : 0;
        table.put(key, new int[] {depth, best, bound, bestMove});
        return best;
    }

    /** side 차례 국면의 정적 평가(side 기준). */
    int evaluate(int side) {
        int opp = 3 - side;
        long mine = 0;
        long theirs = 0;
        int bestMine = 0;
        for (int y = boxY0; y <= boxY1; y++) for (int q = y * N + boxX0, end = y * N + boxX1; q <= end; q++) {
            if (near[q] == 0 || cells[q] != Board.EMPTY) {
                continue;
            }
            int a = score[side][q];
            int b = score[opp][q];
            bestMine = Math.max(bestMine, a);
            mine += Math.min(a, CAP);
            theirs += Math.min(b, CAP);
        }
        if (bestMine >= S_FOUR_THREE) {
            return WIN / 2;                                 // 상대에게 5 가 없으니 열린 4 · 4-3 으로 이긴다
        }
        return (int) (mine * 12 / 10 - theirs);
    }

    private static final int CAP = 10_000;

    /**
     * side 의 후보 수 — 상대가 열린 4·4-3·44 를 만들 수 있으면(위협) 내 4 와 상대 점수 높은 칸(막는 자리)만, 아니면 내 점수 +
     * 상대 점수 순. 루트는 {@link #legal} 로, 그 밖은 금수 판정으로 거른다.
     */
    int[] genMoves(int side, int width, boolean root) {
        int opp = 3 - side;
        boolean threatened = threatCount[opp] > 0;
        long[] keyed = new long[(boxX1 - boxX0 + 1) * (boxY1 - boxY0 + 1)];
        int n = 0;
        for (int y = boxY0; y <= boxY1; y++) for (int q = y * N + boxX0, end = y * N + boxX1; q <= end; q++) {
            if (near[q] == 0 || cells[q] != Board.EMPTY) {
                continue;
            }
            long pri;
            if (threatened) {
                // 막는 자리(상대 점수 순)와 내 4. 루트에서는 막지 않는 4 를 빼다 — 이기는 4 는 VCF 가 이미 봤고, 그 밖의 4 는
                // 수만 늦추며 나중에 쓸 4 를 버린다(지평선 효과로 4 부터 두었다). 안쪽에서는 역공 4 를 응수로 본다.
                boolean myFour = !root && isFour(side, q);
                if (!myFour && score[opp][q] < 1_000) {
                    continue;
                }
                pri = myFour ? (long) score[side][q] + S_FIVE : score[opp][q];
            } else {
                pri = (long) score[side][q] + score[opp][q];
            }
            // 점수 내림차순, 같으면 칸 번호 오름차순.
            keyed[n++] = (-pri << 8) | q;
        }
        int[] out = new int[Math.min(n, width)];
        int k = 0;
        for (int i = 0; i < n && k < out.length; i++) {
            // 부분 선택 — 남은 것 중 가장 작은 키(= 점수 큰 칸)를 앞으로.
            int min = i;
            for (int j = i + 1; j < n; j++) {
                if (keyed[j] < keyed[min]) {
                    min = j;
                }
            }
            long tmp = keyed[i];
            keyed[i] = keyed[min];
            keyed[min] = tmp;
            int q = (int) (keyed[i] & 0xFF);
            if (root ? legal.contains(pointOf(q)) : canPlay(q, side)) {
                out[k++] = q;
            }
        }
        return Arrays.copyOf(out, k);
    }

    // ---------------------------------------------------------------- VCF · VCT

    /** c 의 VCF 첫 수(c 차례), 없으면 -1. 상대 막는 수로 상대에게 4 가 생기면 그 갈래는 실패로 본다(보수적). */
    int vcf(int c, int depth) {
        if (exhausted()) {
            return -1;
        }
        int f = anyFive(c);
        if (f >= 0) {
            return f;
        }
        int o = 3 - c;
        if (depth <= 0 || anyFive(o) >= 0) {
            return -1;
        }
        long key = hash * 3 + c;
        Integer failed = vcfFail.get(key);
        if (failed != null && failed >= depth) {
            return -1;
        }
        int[] fours = ranked(c, true);
        for (int m : fours) {
            if (exhausted()) {
                return -1;
            }
            if (!canPlay(m, c)) {
                continue;
            }
            make(m, c);
            boolean win = false;
            int[] w = fivePointsThrough(m, c);
            if (w.length >= 2) {
                win = true;
            } else if (w.length == 1) {
                int b = w[0];
                if (!canPlay(b, o)) {
                    win = true;
                } else {
                    make(b, o);
                    win = fivePointsThrough(b, o).length == 0 && vcf(c, depth - 1) >= 0;
                    undo(b);
                }
            }
            undo(m);
            if (win) {
                return m;
            }
        }
        if (!exhausted()) {
            vcfFail.put(key, depth);
        }
        return -1;
    }

    /**
     * c 의 VCT 첫 수(c 차례), 없으면 -1 — 4 나 열린 3 을 두고, 상대의 모든 응수(위협을 끊는 줄 위 빈칸, 상대의 4)에 대해 이어서
     * 이길 때만 성공.
     */
    int vct(int c, int depth) {
        if (exhausted()) {
            return -1;
        }
        int f = vcf(c, params.vcfDepth());
        if (f >= 0) {
            return f;
        }
        int o = 3 - c;
        if (depth <= 0 || anyFive(o) >= 0) {
            return -1;
        }
        long key = hash * 3 + c;
        Integer failed = vctFail.get(key);
        if (failed != null && failed >= depth) {
            return -1;
        }
        int[] threats = ranked(c, false);
        int tried = 0;
        for (int t : threats) {
            if (exhausted()) {
                return -1;
            }
            if (tried >= params.vctWidth()) {
                break;
            }
            if (!canPlay(t, c)) {
                continue;
            }
            tried++;
            make(t, c);
            boolean win = defenderToMove(c, depth);
            undo(t);
            if (win) {
                return t;
            }
        }
        if (!exhausted()) {
            vctFail.put(key, depth);
        }
        return -1;
    }

    /** 상대(o) 차례, c 가 위협 중 — c 가 끝내 이기는가. */
    private boolean defenderToMove(int c, int depth) {
        if (exhausted()) {
            return false;
        }
        int o = 3 - c;
        if (anyFive(o) >= 0) {
            return false;
        }
        int[] cf = fivePoints(c);
        if (cf.length >= 2) {
            return true;
        }
        if (cf.length == 1) {
            int b = cf[0];
            if (!canPlay(b, o)) {
                return true;
            }
            make(b, o);
            boolean r = attackerToMove(c, depth - 1);
            undo(b);
            return r;
        }
        int[] winPts = winLevelPoints(c);
        if (winPts.length == 0) {
            return false;                                   // 위협이 아니다(흑의 거짓 3 등)
        }
        int[] replies = defenses(c, winPts);
        boolean any = false;
        for (int r : replies) {
            if (!canPlay(r, o)) {
                continue;
            }
            any = true;
            make(r, o);
            boolean win = attackerToMove(c, depth - 1);
            undo(r);
            if (!win) {
                return false;
            }
        }
        return any || !exhausted();
    }

    /** c 차례 — c 가 끝내 이기는가. */
    private boolean attackerToMove(int c, int depth) {
        if (exhausted()) {
            return false;
        }
        if (anyFive(c) >= 0) {
            return true;
        }
        int o = 3 - c;
        int[] of = fivePoints(o);
        if (of.length >= 2) {
            return false;
        }
        if (of.length == 1) {
            int b = of[0];
            if (!canPlay(b, c)) {
                return false;
            }
            make(b, c);
            boolean r = defenderToMove(c, depth - 1);
            undo(b);
            return r;
        }
        for (int w : winLevelPoints(c)) {
            if (score[c][w] >= S_WIN && canPlay(w, c)) {
                return true;
            }
        }
        return vct(c, depth) >= 0;
    }

    /** c 의 이기는 위협(열린 4·44 를 만드는 자리)을 끊을 수 있는 응수 후보 — 그 자리를 지나는 4 줄 위 빈칸 + 상대의 4. */
    private int[] defenses(int c, int[] winPts) {
        int o = 3 - c;
        boolean[] seen = new boolean[CELLS];
        int[] out = new int[CELLS];
        int n = 0;
        for (int w : winPts) {
            int wx = w % N;
            int wy = w / N;
            for (int d = 0; d < 4; d++) {
                byte t = type[c][d][w];
                if (t != LinePatterns.OPEN_FOUR && t != LinePatterns.FOUR) {
                    continue;
                }
                for (int k = -5; k <= 5; k++) {
                    int x = wx + k * DX[d];
                    int y = wy + k * DY[d];
                    if (x < 0 || x >= N || y < 0 || y >= N) {
                        continue;
                    }
                    int q = y * N + x;
                    if (cells[q] == Board.EMPTY && !seen[q]) {
                        seen[q] = true;
                        out[n++] = q;
                    }
                }
            }
        }
        for (int y = boxY0; y <= boxY1; y++) for (int q = y * N + boxX0, end = y * N + boxX1; q <= end; q++) {
            if (!seen[q] && near[q] > 0 && cells[q] == Board.EMPTY && isFour(o, q)) {
                seen[q] = true;
                out[n++] = q;
            }
        }
        int[] r = Arrays.copyOf(out, n);
        Arrays.sort(r);
        return r;
    }

    /** c 가 열린 4·44 를 만드는 자리(칸 번호 순). */
    private int[] winLevelPoints(int c) {
        if (threatCount[c] == 0) {
            return NO_POINTS;
        }
        int[] out = new int[8];
        int n = 0;
        for (int q = 0; q < CELLS; q++) {
            if (score[c][q] >= S_WIN && score[c][q] < S_FIVE) {
                if (n == out.length) {
                    out = Arrays.copyOf(out, n * 2);
                }
                out[n++] = q;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** c 의 4(fourOnly) 또는 4·열린 3 을 만드는 후보, 점수 내림차순 → 칸 번호. */
    private int[] ranked(int c, boolean fourOnly) {
        long[] keyed = new long[(boxX1 - boxX0 + 1) * (boxY1 - boxY0 + 1)];
        int n = 0;
        for (int y = boxY0; y <= boxY1; y++) for (int q = y * N + boxX0, end = y * N + boxX1; q <= end; q++) {
            if (near[q] == 0 || cells[q] != Board.EMPTY || score[c][q] >= S_FIVE) {
                continue;
            }
            boolean four = isFour(c, q);
            boolean three = !fourOnly && hasType(c, q, LinePatterns.OPEN_THREE);
            if (four || three) {
                keyed[n++] = ((long) -score[c][q] << 8) | q;
            }
        }
        Arrays.sort(keyed, 0, n);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = (int) (keyed[i] & 0xFF);
        }
        return out;
    }

    // ---------------------------------------------------------------- 판·점수

    private boolean isFour(int c, int q) {
        return hasType(c, q, LinePatterns.FOUR) || hasType(c, q, LinePatterns.OPEN_FOUR);
    }

    private boolean hasType(int c, int q, byte t) {
        for (int d = 0; d < 4; d++) {
            if (type[c][d][q] == t) {
                return true;
            }
        }
        return false;
    }

    /** c 가 지금 5 를 만드는 첫 칸, 없으면 -1. */
    int anyFive(int c) {
        if (fiveCount[c] == 0) {
            return -1;
        }
        int[] s = score[c];
        for (int q = 0; q < CELLS; q++) {
            if (s[q] >= S_FIVE) {
                return q;
            }
        }
        return -1;
    }

    /** c 가 5 를 만드는 칸들(2 개에서 멈춘다). */
    private int[] fivePoints(int c) {
        if (fiveCount[c] == 0) {
            return NO_POINTS;
        }
        int[] s = score[c];
        int first = -1;
        for (int q = 0; q < CELLS; q++) {
            if (s[q] >= S_FIVE) {
                if (first >= 0) {
                    return new int[] {first, q};
                }
                first = q;
            }
        }
        return first < 0 ? new int[0] : new int[] {first};
    }

    /** 방금 둔 p 를 지나는 줄에서 c 가 5 를 만드는 칸들(2 개에서 멈춘다). */
    private int[] fivePointsThrough(int p, int c) {
        if (fiveCount[c] == 0) {
            return NO_POINTS;
        }
        int px = p % N;
        int py = p / N;
        int first = -1;
        for (int d = 0; d < 4; d++) {
            for (int k = -4; k <= 4; k++) {
                int x = px + k * DX[d];
                int y = py + k * DY[d];
                if (k == 0 || x < 0 || x >= N || y < 0 || y >= N) {
                    continue;
                }
                int q = y * N + x;
                if (score[c][q] >= S_FIVE && q != first) {
                    if (first >= 0) {
                        return new int[] {first, q};
                    }
                    first = q;
                }
            }
        }
        return first < 0 ? new int[0] : new int[] {first};
    }

    boolean canPlay(int q, int c) {
        if (cells[q] != Board.EMPTY) {
            return false;
        }
        if (c != Board.BLACK || !rule.hasForbiddenMoves() || !maybeForbidden[q]) {
            return true;
        }
        nodes++;
        return ForbiddenRule.checkCells(cells, q % N, q / N, rule).isEmpty();
    }

    void make(int q, int c) {
        nodes++;
        cells[q] = (byte) c;
        hash ^= ZOBRIST[c][q];
        addNear(q, 1);
        boxStack[boxTop++] = boxX0;
        boxStack[boxTop++] = boxX1;
        boxStack[boxTop++] = boxY0;
        boxStack[boxTop++] = boxY1;
        growBox(q);
        setScore(1, q, 0);
        setScore(2, q, 0);
        shift(q, c, 1);
    }

    void undo(int q) {
        int c = cells[q];
        cells[q] = Board.EMPTY;
        hash ^= ZOBRIST[c][q];
        addNear(q, -1);
        boxY1 = boxStack[--boxTop];
        boxY0 = boxStack[--boxTop];
        boxX1 = boxStack[--boxTop];
        boxX0 = boxStack[--boxTop];
        shift(q, c, -1);
        for (int d = 0; d < 4; d++) {
            type[1][d][q] = blackTable[lineIndex[1][d][q]];
            type[2][d][q] = LinePatterns.AT_LEAST_FIVE[lineIndex[2][d][q]];
        }
        combine(1, q);
        combine(2, q);
    }

    private void growBox(int q) {
        boxX0 = Math.min(boxX0, Math.max(0, q % N - 2));
        boxX1 = Math.max(boxX1, Math.min(N - 1, q % N + 2));
        boxY0 = Math.min(boxY0, Math.max(0, q / N - 2));
        boxY1 = Math.max(boxY1, Math.min(N - 1, q / N + 2));
    }

    private void addNear(int q, int delta) {
        int qx = q % N;
        int qy = q / N;
        for (int y = Math.max(0, qy - 2); y <= Math.min(N - 1, qy + 2); y++) {
            for (int x = Math.max(0, qx - 2); x <= Math.min(N - 1, qx + 2); x++) {
                near[y * N + x] += delta;
            }
        }
    }

    /**
     * p 에 c 돌이 생기거나(sign 1) 없어질 때(−1) p 를 지나는 네 줄(±5) 칸들의 표 번호를 한 자리씩 고치고 빈칸이면 모양·점수를
     * 다시 낸다. q 에서 본 p 의 오프셋은 −k — c 에게는 "내 돌"(1), 상대에게는 "막힘"(2).
     */
    private void shift(int p, int c, int sign) {
        int o = 3 - c;
        int px = p % N;
        int py = p / N;
        for (int d = 0; d < 4; d++) {
            int[] mine = lineIndex[c][d];
            int[] theirs = lineIndex[o][d];
            byte[] ownTable = c == Board.BLACK ? blackTable : LinePatterns.AT_LEAST_FIVE;
            byte[] oppTable = o == Board.BLACK ? blackTable : LinePatterns.AT_LEAST_FIVE;
            for (int k = -5; k <= 5; k++) {
                if (k == 0) {
                    continue;
                }
                int x = px + k * DX[d];
                int y = py + k * DY[d];
                if (x < 0 || x >= N || y < 0 || y >= N) {
                    continue;
                }
                int q = y * N + x;
                int pow = LinePatterns.POW3[k > 0 ? 5 - k : 4 - k];
                mine[q] += sign * pow;
                theirs[q] += sign * 2 * pow;
                if (cells[q] == Board.EMPTY) {
                    retype(c, d, q, ownTable[mine[q]]);
                    retype(o, d, q, oppTable[theirs[q]]);
                }
            }
        }
    }

    private void retype(int c, int d, int q, byte t) {
        byte old = type[c][d][q];
        if (old == t) {
            return;
        }
        type[c][d][q] = t;
        int key = comboKey[c][q] + (t - old) * POW9[d];
        comboKey[c][q] = key;
        applyKey(c, q, key);
    }

    private int initialIndex(int x, int y, int d, int c) {
        int idx = 0;
        for (int i = 0; i < 10; i++) {
            int k = LinePatterns.OFFSETS[i];
            int nx = x + k * DX[d];
            int ny = y + k * DY[d];
            int code;
            if (nx < 0 || nx >= N || ny < 0 || ny >= N) {
                code = 2;
            } else {
                byte v = cells[ny * N + nx];
                code = v == Board.EMPTY ? 0 : v == c ? 1 : 2;
            }
            idx += code * LinePatterns.POW3[i];
        }
        return idx;
    }

    private void setScore(int c, int q, int value) {
        int old = score[c][q];
        if (old >= S_FIVE) {
            fiveCount[c]--;
        } else if (old >= S_DOUBLE_THREE) {
            threatCount[c]--;
        }
        if (value >= S_FIVE) {
            fiveCount[c]++;
        } else if (value >= S_DOUBLE_THREE) {
            threatCount[c]++;
        }
        score[c][q] = value;
    }

    /** 칸의 네 방향 모양을 다시 읽어 점수를 낸다. */
    private void combine(int c, int q) {
        int key = 0;
        for (int d = 0; d < 4; d++) {
            key += type[c][d][q] * POW9[d];
        }
        comboKey[c][q] = key;
        applyKey(c, q, key);
    }

    private void applyKey(int c, int q, int key) {
        if (c == Board.BLACK) {
            setScore(c, q, blackCombo[key]);
            maybeForbidden[q] = blackMaybe[key];
        } else {
            setScore(c, q, WHITE_COMBO[key]);
        }
    }

    private static final int[] POW9 = {1, 9, 81, 729};
    private static final int COMBOS = 9 * 9 * 9 * 9;
    private static final int[] WHITE_COMBO = comboScores(OmokRule.FREESTYLE, Board.WHITE, null);

    /** 네 방향 모양 조합(9^4) → 칸 점수. 흑이고 금수 있는 룰이면 금수 근사(금수면 0)를 넣고 maybe 에 "금수일 수도"를 적는다. */
    static int[] comboScores(OmokRule rule, int c, boolean[] maybe) {
        int[] out = new int[COMBOS];
        for (int key = 0; key < COMBOS; key++) {
            int fives = 0;
            int overlines = 0;
            int openFours = 0;
            int fours = 0;
            int openThrees = 0;
            int threes = 0;
            int openTwos = 0;
            int twos = 0;
            for (int d = 0; d < 4; d++) {
                switch ((byte) (key / POW9[d] % 9)) {
                    case LinePatterns.FIVE -> fives++;
                    case LinePatterns.OVERLINE -> overlines++;
                    case LinePatterns.OPEN_FOUR -> openFours++;
                    case LinePatterns.FOUR -> fours++;
                    case LinePatterns.OPEN_THREE -> openThrees++;
                    case LinePatterns.THREE -> threes++;
                    case LinePatterns.OPEN_TWO -> openTwos++;
                    case LinePatterns.TWO -> twos++;
                    default -> {
                    }
                }
            }
            if (fives > 0) {
                out[key] = S_FIVE;
                continue;
            }
            int allFours = fours + openFours;
            if (c == Board.BLACK && rule.hasForbiddenMoves()) {
                boolean forbidden = (rule.forbidsOverline() && overlines > 0)
                        || (rule.forbidsDoubleFour() && allFours >= 2)
                        || (rule.forbidsDoubleThree() && openThrees >= 2);
                maybe[key] = forbidden || (rule.forbidsDoubleFour() && openFours > 0);
                if (forbidden) {
                    continue;                               // 점수 0
                }
            }
            int sc;
            if (openFours > 0 || allFours >= 2) {
                sc = S_WIN;
            } else if (allFours == 1 && openThrees >= 1) {
                sc = S_FOUR_THREE;
            } else if (openThrees >= 2) {
                sc = S_DOUBLE_THREE;
            } else {
                sc = allFours * 1_500 + openThrees * 1_200 + threes * 200 + openTwos * 150 + twos * 30;
                if (allFours == 1 && (threes + openTwos) > 0) {
                    sc += 300;
                }
                if (openThrees == 1 && (threes + openTwos) > 0) {
                    sc += 300;
                }
            }
            out[key] = sc + 1;
        }
        return out;
    }

    private static final int[] NO_POINTS = new int[0];

    private static Point pointOf(int q) {
        return new Point(q % N, q / N);
    }

    private static long[][] zobrist() {
        SplittableRandom r = new SplittableRandom(0x6F6D6F6BL);
        long[][] z = new long[3][CELLS];
        for (int c = 1; c <= 2; c++) {
            for (int q = 0; q < CELLS; q++) {
                z[c][q] = r.nextLong();
            }
        }
        return z;
    }
}
