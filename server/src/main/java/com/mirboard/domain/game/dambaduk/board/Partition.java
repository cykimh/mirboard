package com.mirboard.domain.game.dambaduk.board;

import java.util.ArrayList;
import java.util.List;

/**
 * 담만으로 나눈 터 (룰 §1·§5) — 칸을 담 없는 면으로 이은 연결 요소. 돌은 경계가 아니다. 터 번호는 그 터의 가장 작은 칸 번호 순(칸 번호를
 * 차례로 훑어 처음 만나는 칸이 새 터를 연다)이라 같은 담이면 늘 같은 번호다.
 *
 * @param regionOf 칸 번호 → 터 번호
 * @param regions  터 번호 → 칸 번호 목록(오름차순)
 */
public record Partition(int[] regionOf, List<List<Integer>> regions) {

    public static Partition of(Walls walls) {
        int[] regionOf = new int[Grid.CELLS];
        java.util.Arrays.fill(regionOf, -1);
        List<List<Integer>> regions = new ArrayList<>();
        int[] queue = new int[Grid.CELLS];
        for (int start = 0; start < Grid.CELLS; start++) {
            if (regionOf[start] >= 0) {
                continue;
            }
            int id = regions.size();
            List<Integer> cells = new ArrayList<>();
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            regionOf[start] = id;
            while (head < tail) {
                int idx = queue[head++];
                cells.add(idx);
                Cell c = Cell.ofIndex(idx);
                for (Dir d : Dir.values()) {
                    if (walls.blocked(c, d)) {
                        continue;
                    }
                    int n = c.step(d).index();
                    if (regionOf[n] < 0) {
                        regionOf[n] = id;
                        queue[tail++] = n;
                    }
                }
            }
            cells.sort(null);
            regions.add(List.copyOf(cells));
        }
        return new Partition(regionOf, List.copyOf(regions));
    }

    public int regionOf(Cell c) {
        return regionOf[c.index()];
    }

    public int size() {
        return regions.size();
    }
}
