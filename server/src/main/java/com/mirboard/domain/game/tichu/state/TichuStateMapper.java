package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.card.Card;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 마스터 {@link TichuState} 를 공개 가능한 {@link TableView} 와 본인 한정
 * {@link PrivateHand} 로 분리한다 — D-01 의 State Hiding 원칙 직렬화 단계의 마지막
 * 방어선. 공개 뷰는 손패 카드를 절대 노출하지 않고 장수(handCount)만 포함.
 *
 * <p>Phase 5c 부터 매치 누적 정보 (matchScores, roundNumber) 가 같이 노출되므로
 * 매퍼 호출 시 두 값을 함께 전달해야 한다.
 */
public final class TichuStateMapper {

    public static final Map<Team, Integer> ZERO_MATCH_SCORES;

    static {
        Map<Team, Integer> m = new EnumMap<>(Team.class);
        m.put(Team.A, 0);
        m.put(Team.B, 0);
        ZERO_MATCH_SCORES = Map.copyOf(m);
    }

    private TichuStateMapper() {
    }

    public static TableView toTableView(TichuState state,
                                        Map<Team, Integer> matchScores,
                                        int roundNumber) {
        return switch (state) {
            case TichuState.Dealing d -> dealingToTableView(d, matchScores, roundNumber);
            case TichuState.Passing p -> passingToTableView(p, matchScores, roundNumber);
            case TichuState.Playing p -> playingToTableView(p, matchScores, roundNumber);
            case TichuState.RoundEnd r -> roundEndToTableView(r, matchScores, roundNumber);
        };
    }

    public static PrivateHand toPrivateHand(TichuState state, int seat) {
        return new PrivateHand(seat, state.players().get(seat).hand());
    }

    public static String phaseName(TichuState state) {
        return switch (state) {
            case TichuState.Dealing __ -> "DEALING";
            case TichuState.Passing __ -> "PASSING";
            case TichuState.Playing __ -> "PLAYING";
            case TichuState.RoundEnd __ -> "ROUND_END";
        };
    }

    // ---------- internals ----------

    private static TableView dealingToTableView(TichuState.Dealing dealing,
                                                Map<Team, Integer> matchScores,
                                                int roundNumber) {
        Map<Integer, Integer> handCounts = new HashMap<>();
        Map<Integer, TichuDeclaration> declarations = new HashMap<>();
        for (PlayerState p : dealing.players()) {
            handCounts.put(p.seat(), p.handSize());
            declarations.put(p.seat(), p.declaration());
        }
        List<Integer> ready = dealing.ready().stream().sorted().toList();
        return new TableView(
                "DEALING",
                dealing.phaseCardCount(),
                ready,
                List.of(),
                -1,
                handCounts,
                null,
                -1,
                declarations,
                liveTrickScores(dealing.players()),
                matchScores,
                roundNumber,
                List.of(),
                null);
    }

    private static TableView passingToTableView(TichuState.Passing passing,
                                                Map<Team, Integer> matchScores,
                                                int roundNumber) {
        Map<Integer, Integer> handCounts = new HashMap<>();
        Map<Integer, TichuDeclaration> declarations = new HashMap<>();
        for (PlayerState p : passing.players()) {
            handCounts.put(p.seat(), p.handSize());
            declarations.put(p.seat(), p.declaration());
        }
        List<Integer> submitted = passing.submitted().keySet().stream().sorted().toList();
        return new TableView(
                "PASSING",
                0,
                List.of(),
                submitted,
                -1,
                handCounts,
                null,
                -1,
                declarations,
                liveTrickScores(passing.players()),
                matchScores,
                roundNumber,
                List.of(),
                null);
    }

    private static TableView playingToTableView(TichuState.Playing playing,
                                                Map<Team, Integer> matchScores,
                                                int roundNumber) {
        TrickState trick = playing.trick();
        Map<Integer, Integer> handCounts = new HashMap<>();
        Map<Integer, TichuDeclaration> declarations = new HashMap<>();
        for (PlayerState p : playing.players()) {
            handCounts.put(p.seat(), p.handSize());
            declarations.put(p.seat(), p.declaration());
        }
        return new TableView(
                "PLAYING",
                0,
                List.of(),
                List.of(),
                trick.currentTurnSeat(),
                handCounts,
                trick.currentTop(),
                trick.currentTopSeat(),
                declarations,
                liveTrickScores(playing.players()),
                matchScores,
                roundNumber,
                finishingOrder(playing.players()),
                trick.hasActiveWish() ? trick.activeWish().rank() : null);
    }

    private static TableView roundEndToTableView(TichuState.RoundEnd r,
                                                 Map<Team, Integer> matchScores,
                                                 int roundNumber) {
        Map<Integer, Integer> handCounts = new HashMap<>();
        Map<Integer, TichuDeclaration> declarations = new HashMap<>();
        for (PlayerState p : r.players()) {
            handCounts.put(p.seat(), p.handSize());
            declarations.put(p.seat(), p.declaration());
        }
        Map<Team, Integer> scores = new EnumMap<>(Team.class);
        scores.put(Team.A, r.teamAScore());
        scores.put(Team.B, r.teamBScore());
        return new TableView(
                "ROUND_END",
                0,
                List.of(),
                List.of(),
                -1,
                handCounts,
                null,
                -1,
                declarations,
                scores,
                matchScores,
                roundNumber,
                finishingOrder(r.players()),
                null);
    }

    private static Map<Team, Integer> liveTrickScores(List<PlayerState> players) {
        Map<Team, Integer> scores = new EnumMap<>(Team.class);
        scores.put(Team.A, 0);
        scores.put(Team.B, 0);
        for (PlayerState p : players) {
            int pts = p.tricksWon().stream().mapToInt(Card::points).sum();
            scores.merge(p.team(), pts, Integer::sum);
        }
        return scores;
    }

    private static List<Integer> finishingOrder(List<PlayerState> players) {
        List<int[]> pairs = new ArrayList<>();
        for (PlayerState p : players) {
            if (p.isFinished()) pairs.add(new int[]{p.finishedOrder(), p.seat()});
        }
        pairs.sort(Comparator.comparingInt(o -> o[0]));
        return pairs.stream().map(o -> o[1]).toList();
    }
}
