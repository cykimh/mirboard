package com.mirboard.domain.game.onecard.rules;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 순위 (`docs/rules-onecard.md` §11.2). 다 낸 사람 → 살아 있는 사람(남은 장수 적은 순) → 파산자(늦게 파산한
 * 쪽이 위) → 탈주자(서로 동순위). 동순위 다음 순위는 건너뛴다(1, 1, 3).
 */
public final class Ranking {

    private Ranking() {
    }

    /**
     * @param hands        좌석별 손패(탈락자는 빈 목록)
     * @param eliminations 탈락 순서
     * @param finisher     마지막 카드를 낸 좌석, 없으면 −1
     * @return 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
     */
    public static List<Standing> rank(List<List<PlayingCard>> hands, List<Elimination> eliminations, int finisher) {
        List<Entry> entries = new ArrayList<>();
        for (int seat = 0; seat < hands.size(); seat++) {
            Elimination out = find(eliminations, seat);
            if (seat == finisher) {
                entries.add(new Entry(seat, 0, 0, 0, SeatStatus.FINISHED));
            } else if (out == null) {
                int left = hands.get(seat).size();
                entries.add(new Entry(seat, 1, left, left, SeatStatus.ALIVE));
            } else if (out.reason() == Elimination.Reason.BANKRUPT) {
                entries.add(new Entry(seat, 2, -eliminations.indexOf(out), out.cardsHeld(), SeatStatus.BANKRUPT));
            } else {
                entries.add(new Entry(seat, 3, 0, out.cardsHeld(), SeatStatus.DESERTED));
            }
        }
        Comparator<Entry> order = Comparator.comparingInt(Entry::group).thenComparingInt(Entry::key);
        List<Standing> standings = new ArrayList<>();
        for (Entry entry : entries) {
            int ahead = (int) entries.stream().filter(other -> order.compare(other, entry) < 0).count();
            standings.add(new Standing(entry.seat(), ahead + 1, entry.cardsLeft(), entry.status()));
        }
        standings.sort(Comparator.comparingInt(Standing::rank).thenComparingInt(Standing::seat));
        return List.copyOf(standings);
    }

    private static Elimination find(List<Elimination> eliminations, int seat) {
        return eliminations.stream().filter(e -> e.seat() == seat).findFirst().orElse(null);
    }

    /** group 이 앞설수록, 같은 group 이면 key 가 작을수록 위다. */
    private record Entry(int seat, int group, int key, int cardsLeft, SeatStatus status) {
    }
}
