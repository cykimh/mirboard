package com.mirboard.domain.game.yacht.rules;

import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.MatchResult.Placement;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 순위 (`docs/rules-yacht.md` §8.2). 살아 있는 좌석을 총점 내림차순·경쟁 순위(1, 1, 3)로 세우고, 탈주자는 그 아래
 * 모두 같은 최하위다. 같은 순위 안에서는 좌석 오름차순.
 */
public final class Ranking {

    private Ranking() {
    }

    public static MatchResult result(YachtState state, MatchResult.EndReason reason) {
        return new MatchResult(reason, placements(state));
    }

    public static List<Placement> placements(YachtState state) {
        List<Integer> alive = new ArrayList<>(state.aliveSeats());
        alive.sort(Comparator.comparingInt((Integer s) -> -state.card(s).total()).thenComparingInt(s -> s));

        List<Placement> out = new ArrayList<>(state.seatCount());
        int rank = 0;
        int previousTotal = Integer.MIN_VALUE;
        for (int i = 0; i < alive.size(); i++) {
            int seat = alive.get(i);
            int total = state.card(seat).total();
            if (total != previousTotal) {
                rank = i + 1;
                previousTotal = total;
            }
            out.add(new Placement(seat, rank, total, false));
        }
        int desertedRank = alive.size() + 1;
        for (int seat : state.deserted()) {
            out.add(new Placement(seat, desertedRank, state.card(seat).total(), true));
        }
        return List.copyOf(out);
    }
}
