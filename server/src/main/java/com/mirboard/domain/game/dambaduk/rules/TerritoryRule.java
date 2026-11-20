package com.mirboard.domain.game.dambaduk.rules;

import com.mirboard.domain.game.dambaduk.board.Partition;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.state.Region;
import com.mirboard.domain.game.dambaduk.state.RegionKind;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.Territory;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** 터·집·점수 (룰 §5) — 담만으로 나누고, 터마다 돌 주인을 본다. */
public final class TerritoryRule {

    private TerritoryRule() {
    }

    public static Territory compute(Walls walls, List<Stone> stones, int seatCount) {
        Partition p = Partition.of(walls);
        List<TreeSet<Integer>> seatsIn = new ArrayList<>(p.size());
        for (int i = 0; i < p.size(); i++) {
            seatsIn.add(new TreeSet<>());
        }
        for (Stone s : stones) {
            seatsIn.get(p.regionOf(s.cell())).add(s.seat());
        }
        int[] scores = new int[seatCount];
        int[] largest = new int[seatCount];
        List<Region> regions = new ArrayList<>(p.size());
        for (int i = 0; i < p.size(); i++) {
            List<Integer> cells = p.regions().get(i);
            TreeSet<Integer> seats = seatsIn.get(i);
            RegionKind kind = seats.isEmpty() ? RegionKind.EMPTY : seats.size() == 1 ? RegionKind.HOME : RegionKind.CONTESTED;
            int owner = kind == RegionKind.HOME ? seats.first() : -1;
            if (owner >= 0) {
                scores[owner] += cells.size();
                largest[owner] = Math.max(largest[owner], cells.size());
            }
            regions.add(new Region(cells, kind, owner, List.copyOf(seats)));
        }
        return new Territory(regions, toList(scores), toList(largest));
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int v : a) {
            out.add(v);
        }
        return out;
    }
}
