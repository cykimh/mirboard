package com.mirboard.domain.game.starsquare.rules;

import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 매치 순위(`docs/rules-starsquare.md` §8.2) — 살아 있는 좌석을 점수 합 → 퍼즐 1등 횟수 내림차순, 같으면 동순위(더 깨지 않음).
 * 탈주한 좌석은 그 아래 모두 같은 최하위.
 */
public final class MatchRanking {

    private MatchRanking() {
    }

    public static MatchResult result(StarSquareState state, MatchResult.EndReason reason) {
        List<Integer> totals = state.totals();
        List<Integer> firsts = state.firsts();
        List<Integer> alive = state.aliveSeats();
        List<MatchResult.Placement> out = new ArrayList<>();
        for (int s = 0; s < state.seatCount(); s++) {
            boolean deserted = !state.alive(s);
            int rank;
            if (deserted) {
                rank = alive.size() + 1;
            } else {
                int better = 0;
                for (int o : alive) {
                    if (totals.get(o) > totals.get(s)
                            || (totals.get(o).equals(totals.get(s)) && firsts.get(o) > firsts.get(s))) {
                        better++;
                    }
                }
                rank = better + 1;
            }
            out.add(new MatchResult.Placement(s, rank, totals.get(s), firsts.get(s), deserted));
        }
        out.sort(Comparator.comparingInt(MatchResult.Placement::rank).thenComparingInt(MatchResult.Placement::seat));
        return new MatchResult(reason, out);
    }
}
