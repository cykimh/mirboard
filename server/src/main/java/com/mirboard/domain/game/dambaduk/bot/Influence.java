package com.mirboard.domain.game.dambaduk.bot;

import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Partition;
import com.mirboard.domain.game.dambaduk.state.Stone;
import java.util.Arrays;

/**
 * 봇 평가 (D-277) — 지금 집(정확한 점수)과 <b>영향권</b>(담을 따라 잰 최단 거리로 가장 가까운 사람의 칸 — 보로노이 땅). 집 안의 칸은
 * 주인만 닿으므로 주인 것, 빈 터는 아무도 닿지 않는다. 돌은 거리 계산을 막지 않는다(어림). 거리가 같으면 아무의 것도 아니다.
 *
 * @param home       좌석별 지금 집 칸 수(룰 §5 와 같은 값)
 * @param influence  좌석별 영향권 칸 수
 * @param contested  다툼 터가 있나 — 없으면 판이 끝난 모양(룰 §6 ①)
 */
record Influence(int[] home, int[] influence, boolean contested) {

    private static final int INF = Integer.MAX_VALUE;

    static Influence of(BotBoard b) {
        int n = b.seatCount;
        Partition p = Partition.of(b.walls);
        int[] mask = new int[p.size()];
        for (Stone s : b.stones) {
            mask[p.regionOf(s.cell())] |= 1 << s.seat();
        }
        int[] home = new int[n];
        boolean contested = false;
        for (int r = 0; r < mask.length; r++) {
            int m = mask[r];
            if (m == 0) {
                continue;
            }
            if (Integer.bitCount(m) == 1) {
                home[Integer.numberOfTrailingZeros(m)] += p.regions().get(r).size();
            } else {
                contested = true;
            }
        }
        int[][] dist = new int[n][Grid.CELLS];
        int[] queue = new int[Grid.CELLS];
        for (int seat = 0; seat < n; seat++) {
            int[] d = dist[seat];
            Arrays.fill(d, INF);
            int head = 0;
            int tail = 0;
            for (Stone s : b.stones) {
                if (s.seat() == seat) {
                    int idx = s.cell().index();
                    if (d[idx] != 0) {
                        d[idx] = 0;
                        queue[tail++] = idx;
                    }
                }
            }
            while (head < tail) {
                int idx = queue[head++];
                Cell c = Cell.ofIndex(idx);
                for (Dir dir : Dir.values()) {
                    if (b.walls.blocked(c, dir)) {
                        continue;
                    }
                    int nIdx = c.step(dir).index();
                    if (d[nIdx] == INF) {
                        d[nIdx] = d[idx] + 1;
                        queue[tail++] = nIdx;
                    }
                }
            }
        }
        int[] influence = new int[n];
        for (int cell = 0; cell < Grid.CELLS; cell++) {
            int best = INF;
            int owner = -1;
            for (int seat = 0; seat < n; seat++) {
                int d = dist[seat][cell];
                if (d < best) {
                    best = d;
                    owner = seat;
                } else if (d == best) {
                    owner = -1;
                }
            }
            if (owner >= 0 && best != INF) {
                influence[owner]++;
            }
        }
        return new Influence(home, influence, contested);
    }
}
