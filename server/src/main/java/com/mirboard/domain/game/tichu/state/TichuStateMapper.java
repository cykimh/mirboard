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
 */
public final class TichuStateMapper {

    private TichuStateMapper() {
    }

    public static TableView toTableView(TichuState state) {
        return switch (state) {
            case TichuState.Playing p -> playingToTableView(p);
            case TichuState.Passing p -> passingToTableView(p);
            case TichuState.RoundEnd r -> roundEndToTableView(r);
        };
    }

    public static PrivateHand toPrivateHand(TichuState state, int seat) {
        return new PrivateHand(seat, state.players().get(seat).hand());
    }

    public static String phaseName(TichuState state) {
        return switch (state) {
            case TichuState.Playing __ -> "PLAYING";
            case TichuState.Passing __ -> "PASSING";
            case TichuState.RoundEnd __ -> "ROUND_END";
        };
    }

    // ---------- internals ----------

    private static TableView playingToTableView(TichuState.Playing playing) {
        TrickState trick = playing.trick();
        Map<Integer, Integer> handCounts = new HashMap<>();
        Map<Integer, TichuDeclaration> declarations = new HashMap<>();
        for (PlayerState p : playing.players()) {
            handCounts.put(p.seat(), p.handSize());
            declarations.put(p.seat(), p.declaration());
        }
        return new TableView(
                trick.currentTurnSeat(),
                handCounts,
                trick.currentTop(),
                trick.currentTopSeat(),
                declarations,
                liveTrickScores(playing.players()),
                finishingOrder(playing.players()),
                trick.hasActiveWish() ? trick.activeWish().rank() : null);
    }

    private static TableView passingToTableView(TichuState.Passing passing) {
        Map<Integer, Integer> handCounts = new HashMap<>();
        Map<Integer, TichuDeclaration> declarations = new HashMap<>();
        for (PlayerState p : passing.players()) {
            handCounts.put(p.seat(), p.handSize());
            declarations.put(p.seat(), p.declaration());
        }
        return new TableView(
                -1, handCounts, null, -1, declarations,
                liveTrickScores(passing.players()),
                List.of(), null);
    }

    private static TableView roundEndToTableView(TichuState.RoundEnd r) {
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
                -1, handCounts, null, -1, declarations,
                scores, finishingOrder(r.players()), null);
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
