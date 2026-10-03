package com.mirboard.domain.game.tichu.bot;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.TichuEngine;
import com.mirboard.domain.game.tichu.TichuPendingSeats;
import com.mirboard.domain.game.tichu.TurnManager;
import com.mirboard.domain.game.tichu.action.ActionValidator;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.invariant.TichuInvariantChecker;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * D-118 테스트 전용 순수 하네스 — Docker·Spring·Redis 없이 {@link TichuEngine} 만으로 풀매치와
 * 단일 딜을 돌린다.
 *
 * <ul>
 *   <li>분배는 {@code TichuRoundStarter.deal} 과 같은 8+6 분할로 Dealing(8) 을 만든다.</li>
 *   <li>대기 좌석은 운영과 같은 {@link TichuPendingSeats#of} 로 구하고, 그중 최소 좌석이
 *       행동한다(BotScheduler 의 봇 좌석 순서와 같음).</li>
 *   <li>매 액션: validate → apply → {@link TichuInvariantChecker#check}, 상한(라운드당
 *       {@value #MAX_ACTIONS_PER_ROUND} 액션, 매치당 {@value #MAX_ROUNDS_PER_MATCH} 라운드).</li>
 *   <li>덱 RNG 와 봇 RNG 가 분리돼 있어 좌석을 바꾼 두 테이블에서 같은 딜이 나온다(듀플리케이트).</li>
 * </ul>
 */
final class TichuBotArena {

    static final int MAX_ACTIONS_PER_ROUND = 2000;
    static final int MAX_ROUNDS_PER_MATCH = 60;
    static final int TARGET_SCORE = 1000;

    private static final List<Long> PLAYER_IDS = List.of(1L, 2L, 3L, 4L);
    private static final TichuEngine ENGINE = new TichuEngine(new GameContext("sim", PLAYER_IDS));

    /** 한 좌석의 정책. */
    interface Seat {
        Move act(TichuState state, int seat);

        /** 통계에서 이 좌석을 휴리스틱으로 칠지. */
        default boolean heuristic() {
            return false;
        }
    }

    record Move(TichuAction action, boolean fellBack) {
    }

    /** 관찰 훅 (숨김 정보 스크램블 성질 테스트·선언 보정 로그용). */
    interface Observer {
        void beforeAct(TichuState state, int seat, Seat policy);

        default void roundEnded(TichuState.RoundEnd end) {
        }
    }

    static Seat heuristic() {
        return heuristic(HeuristicBotPolicy.Tuning.DEFAULT);
    }

    static Seat heuristic(HeuristicBotPolicy.Tuning tuning) {
        HeuristicBotPolicy policy = new HeuristicBotPolicy(tuning);
        return new Seat() {
            @Override
            public Move act(TichuState state, int seat) {
                HeuristicBotPolicy.Decision d = policy.decide(state, seat);
                return new Move(d.action(), d.fellBack());
            }

            @Override
            public boolean heuristic() {
                return true;
            }
        };
    }

    static Seat random(long seed) {
        RandomBotPolicy policy = new RandomBotPolicy(seed);
        return (state, seat) -> new Move(policy.choose(state, seat), false);
    }

    static Seat greedy() {
        return (state, seat) -> new Move(GreedyBaselinePolicy.choose(state, seat), false);
    }

    // ---------- 통계 ----------

    /** 휴리스틱 좌석 기준 누적 통계. */
    static final class Stats {
        int actions;
        int heuristicDecisions;
        int fellBack;
        final List<Long> heuristicNanos = new ArrayList<>();
        int tichuCalls;
        int tichuMade;
        int grandCalls;
        int grandMade;
        /** 파트너가 선언했는데 내가 1등으로 나가 선언을 깨뜨린 횟수(휴리스틱 좌석). */
        int partnerOutFirstWhileDeclared;
        int rounds;
        int doubleVictories;
        /** 액션 로그 해시 — 두 JVM 실행의 결정성 비교(보조 확인)용. */
        long actionLogHash = 17;

        double tichuRate() {
            return tichuCalls == 0 ? Double.NaN : (double) tichuMade / tichuCalls;
        }

        double grandRate() {
            return grandCalls == 0 ? Double.NaN : (double) grandMade / grandCalls;
        }
    }

    record RoundResult(RoundScore score, TichuState.RoundEnd end) {
        int teamScore(Team team) {
            return score.scoreOf(team);
        }
    }

    record MatchResult(Team winner, int rounds, int cumulativeA, int cumulativeB) {
    }

    // ---------- 실행 ----------

    private Observer observer;

    TichuBotArena observe(Observer o) {
        this.observer = o;
        return this;
    }

    MatchResult playMatch(Seat[] seats, long deckSeed, Stats stats) {
        Random deckRng = new Random(deckSeed);
        TichuMatchState match = TichuMatchState.initial(PLAYER_IDS, TARGET_SCORE);
        int rounds = 0;
        while (!match.isMatchOver()) {
            if (++rounds > MAX_ROUNDS_PER_MATCH) {
                throw new AssertionError("match stalled: > " + MAX_ROUNDS_PER_MATCH + " rounds");
            }
            RoundResult round = playRound(seats, deckRng, stats);
            match = match.withRoundCompleted(round.score());
        }
        return new MatchResult(match.winningTeam(), rounds, match.cumulativeA(), match.cumulativeB());
    }

    /** 듀플리케이트용 단일 딜. 같은 deckSeed 면 좌석별로 같은 카드가 간다. */
    RoundResult playDeal(Seat[] seats, long deckSeed, Stats stats) {
        return playRound(seats, new Random(deckSeed), stats);
    }

    RoundResult playRound(Seat[] seats, Random deckRng, Stats stats) {
        TichuState state = initialDealing(Deck.shuffled(deckRng).cards());
        RoundScore score = null;
        int actions = 0;
        while (!(state instanceof TichuState.RoundEnd)) {
            List<Integer> pending = TichuPendingSeats.of(state);
            if (pending.isEmpty()) {
                throw new AssertionError("stall: no pending seat in " + state.getClass().getSimpleName());
            }
            int seat = pending.get(0);
            Seat policy = seats[seat];
            if (observer != null) observer.beforeAct(state, seat, policy);

            long t0 = System.nanoTime();
            Move move = policy.act(state, seat);
            long elapsed = System.nanoTime() - t0;
            if (policy.heuristic()) {
                stats.heuristicDecisions++;
                stats.heuristicNanos.add(elapsed);
                if (move.fellBack()) stats.fellBack++;
            }
            if (move.action() == null) {
                throw new AssertionError("pending seat " + seat + " returned null in "
                        + state.getClass().getSimpleName());
            }
            ActionValidator.validate(state, seat, move.action());
            TichuEngine.Result result = ENGINE.apply(state, seat, move.action());
            TichuInvariantChecker.check(result.newState());
            for (var e : result.events()) {
                if (e instanceof TichuEvent.RoundEnded ended) score = ended.score();
            }
            state = result.newState();
            stats.actions++;
            stats.actionLogHash = stats.actionLogHash * 31 + (seat + ":" + move.action()).hashCode();
            if (++actions > MAX_ACTIONS_PER_ROUND) {
                throw new AssertionError("round stalled: > " + MAX_ACTIONS_PER_ROUND + " actions");
            }
        }
        if (score == null) throw new AssertionError("RoundEnd without RoundEnded event");
        TichuState.RoundEnd end = (TichuState.RoundEnd) state;
        recordDeclarations(seats, end, stats);
        if (observer != null) observer.roundEnded(end);
        stats.rounds++;
        if (score.doubleVictory()) stats.doubleVictories++;
        return new RoundResult(score, end);
    }

    private static void recordDeclarations(Seat[] seats, TichuState.RoundEnd end, Stats stats) {
        for (PlayerState p : end.players()) {
            if (!seats[p.seat()].heuristic()) continue;
            boolean made = p.finishedOrder() == 1;
            if (p.declaration() == TichuDeclaration.TICHU) {
                stats.tichuCalls++;
                if (made) stats.tichuMade++;
            } else if (p.declaration() == TichuDeclaration.GRAND_TICHU) {
                stats.grandCalls++;
                if (made) stats.grandMade++;
            }
            PlayerState partner = end.players().get(TurnManager.partnerOf(p.seat()));
            if (made && p.declaration() == TichuDeclaration.NONE
                    && partner.declaration() != TichuDeclaration.NONE) {
                stats.partnerOutFirstWhileDeclared++;
            }
        }
    }

    /** {@code TichuRoundStarter.deal} 과 같은 분할: 좌석 s 는 [14s, 14s+8) 공개, [14s+8, 14s+14) 보류. */
    static TichuState.Dealing initialDealing(List<Card> shuffled) {
        List<PlayerState> players = new ArrayList<>(TurnManager.SEATS);
        Map<Integer, List<Card>> reserved = new LinkedHashMap<>();
        for (int seat = 0; seat < TurnManager.SEATS; seat++) {
            int from = seat * 14;
            players.add(PlayerState.initial(seat, List.copyOf(shuffled.subList(from, from + 8))));
            reserved.put(seat, List.copyOf(shuffled.subList(from + 8, from + 14)));
        }
        return new TichuState.Dealing(players, 8, Set.of(), reserved);
    }
}
