package com.mirboard.domain.game.wordtemp.rules;

import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.MatchResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Standing;
import java.util.ArrayList;
import java.util.List;

/**
 * 순위(`docs/rules-wordtemp.md` §8). 총점 높은 순, 같으면 공동 순위(1, 1, 3 식) — 추가 동점 규칙 없음. 빠진 좌석은 점수와 무관하게 모두 같은
 * 최하위(살아 있는 좌석 수 + 1). 빠진 사람 없이 모두 같은 점수면 무승부.
 */
public final class MatchRanking {

    private MatchRanking() {
    }

    public static MatchResult result(List<Seat> seats, EndReason reason) {
        int aliveCount = (int) seats.stream().filter(Seat::alive).count();
        List<Standing> standings = new ArrayList<>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            Seat seat = seats.get(i);
            int rank;
            if (seat.alive()) {
                rank = 1 + (int) seats.stream().filter(o -> o.alive() && o.total() > seat.total()).count();
            } else {
                rank = aliveCount + 1;
            }
            standings.add(new Standing(i, rank, seat.total(), seat.setterPoints(), seat.guessPoints(),
                    !seat.alive()));
        }
        boolean draw = aliveCount == seats.size() && seats.size() > 1
                && seats.stream().mapToInt(Seat::total).distinct().count() == 1;
        return new MatchResult(reason, standings, draw);
    }
}
