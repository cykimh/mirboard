package com.mirboard.domain.game.starsquare.rules;

import com.mirboard.domain.game.starsquare.state.SeatBoard;
import com.mirboard.domain.game.starsquare.state.SeatResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 퍼즐 하나의 순위·점수(`docs/rules-starsquare.md` §8.1).
 *
 * <ol>
 *   <li>완성한 살아 있는 좌석: 완성 순서대로 1, 2, 3 … (다시 번호를 매긴다 — 먼저 완성하고 탈주한 좌석이 빠진다)</li>
 *   <li>못 한 살아 있는 좌석: 그 아래, 맞은 줄 → 채운 칸 내림차순, 같으면 동순위(경쟁 순위)</li>
 *   <li>탈주한 좌석: 맨 아래 모두 같은 순위, 0점</li>
 * </ol>
 * 점수 = 매치 좌석 수 − 순위.
 */
public final class PuzzleRanking {

    private PuzzleRanking() {
    }

    /** 좌석 순서(0..좌석 수−1)의 결과. */
    public static List<SeatResult> rank(StarSquareState state) {
        int seats = state.seatCount();
        List<Integer> alive = state.aliveSeats();
        List<Integer> solved = new ArrayList<>(alive.stream().filter(s -> state.board(s).solved()).toList());
        solved.sort(Comparator.comparingInt(s -> state.board(s).solvePlace()));
        List<Integer> unsolved = alive.stream().filter(s -> !state.board(s).solved()).toList();

        int[] rank = new int[seats];
        for (int i = 0; i < solved.size(); i++) {
            rank[solved.get(i)] = i + 1;
        }
        for (int s : unsolved) {
            SeatBoard me = state.board(s);
            int better = 0;
            for (int o : unsolved) {
                SeatBoard other = state.board(o);
                if (other.lines() > me.lines() || (other.lines() == me.lines() && other.filled() > me.filled())) {
                    better++;
                }
            }
            rank[s] = solved.size() + better + 1;
        }
        int bottom = alive.size() + 1;
        List<SeatResult> out = new ArrayList<>(seats);
        for (int s = 0; s < seats; s++) {
            SeatBoard b = state.board(s);
            boolean deserted = !state.alive(s);
            int r = deserted ? bottom : rank[s];
            int points = deserted ? 0 : seats - r;
            boolean solvedHere = !deserted && b.solved();
            out.add(new SeatResult(s, r, points, solvedHere, solvedHere ? b.elapsedMs() : -1, b.filled(), b.lines(),
                    deserted));
        }
        return List.copyOf(out);
    }
}
