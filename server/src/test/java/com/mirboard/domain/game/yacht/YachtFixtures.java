package com.mirboard.domain.game.yacht;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.state.TurnState;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/** 요트 엔진 테스트 공용 도우미. */
public final class YachtFixtures {

    public static final long T0 = 1_000_000L;

    private YachtFixtures() {
    }

    /** 좌석 {@code seats} 개, {@code bots} 좌석은 봇. */
    public static YachtEngine timedEngine(int seats, int turnSeconds, Integer... bots) {
        List<Long> players = LongStream.rangeClosed(1, seats).boxed().toList();
        return new YachtEngine(new GameContext("room-1", players, 0, 0, List.of(bots), turnSeconds));
    }

    public static YachtEngine engine(int seats, Integer... bots) {
        return timedEngine(seats, 0, bots);
    }

    /** 점수표 순서대로 앞의 {@code filled} 칸을 0 점으로 채운 점수표. */
    public static Scorecard filled(int filled) {
        Scorecard card = Scorecard.EMPTY;
        Category[] all = Category.values();
        for (int i = 0; i < filled; i++) {
            card = card.with(all[i], 0);
        }
        return card;
    }

    /** {@code empty} 하나만 비우고 나머지를 0 점으로 채운 점수표. */
    public static Scorecard allBut(Category empty) {
        Scorecard card = Scorecard.EMPTY;
        for (Category c : Category.values()) {
            if (c != empty) {
                card = card.with(c, 0);
            }
        }
        return card;
    }

    /**
     * 라운드 {@code round} 에 좌석 {@code turnSeat} 차례인 열린 판. 앞 좌석은 round 칸, 나머지는 round−1 칸(0 점).
     */
    public static YachtState openState(int seats, int round, int turnSeat) {
        List<Scorecard> cards = IntStream.range(0, seats)
                .mapToObj(s -> filled(s < turnSeat ? round : round - 1))
                .toList();
        return new YachtState(cards, round, turnSeat, TurnState.fresh(T0), List.of(), 0, null);
    }

    public static YachtState withTurn(YachtState s, Dice dice, HoldMask held, int rolls) {
        return new YachtState(s.cards(), s.round(), s.turnSeat(), new TurnState(dice, held, rolls, s.turn().startedAt()),
                s.deserted(), s.actionCount(), s.result());
    }

    public static YachtState withCard(YachtState s, int seat, Scorecard card) {
        List<Scorecard> cards = new ArrayList<>(s.cards());
        cards.set(seat, card);
        return new YachtState(cards, s.round(), s.turnSeat(), s.turn(), s.deserted(), s.actionCount(), s.result());
    }

    public static YachtState withDeserted(YachtState s, Integer... seats) {
        return new YachtState(s.cards(), s.round(), s.turnSeat(), s.turn(), List.of(seats), s.actionCount(),
                s.result());
    }
}
