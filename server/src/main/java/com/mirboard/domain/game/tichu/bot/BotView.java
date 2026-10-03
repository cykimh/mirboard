package com.mirboard.domain.game.tichu.bot;

import com.mirboard.domain.game.tichu.TurnManager;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;

/**
 * D-118 — 봇 판단용 공개 정보 투영. {@link #of} 가 {@link TichuState} 를 읽는 <b>유일한</b>
 * 경로다. 봇이 사람을 상대로 치팅하지 않도록(State Hiding) 여기 담기지 않은 것은 정책이 볼
 * 수 없다.
 *
 * <p>담는 것: 본인 손패, 좌석별 장수·선언·완주 순서, Dealing 의 장수 단계와 내 ready 여부,
 * Playing 의 공개 트릭(top·차례·패스 좌석·활성 소원·트릭 점수), 공개적으로 나온 카드
 * (모든 tricksWon + 진행 중 트릭 카드), 내 용 양도 보류 여부.
 *
 * <p>담지 않는 것: 본인 외 손패, {@code Dealing.reservedSecondHalf}(내 몫 포함 — 그랜드
 * 판단은 보이는 8장만), 남의 {@code Passing.submitted}.
 */
final class BotView {

    enum Phase { DEALING, PASSING, PLAYING, ROUND_END }

    /** 56장 전체 마스크. */
    static final long ALL = (1L << 56) - 1;

    private final int seat;
    private final Phase phase;
    private final long hand;
    private final int[] sizes = new int[TurnManager.SEATS];
    private final TichuDeclaration[] declarations = new TichuDeclaration[TurnManager.SEATS];
    private final int[] finishedOrder = new int[TurnManager.SEATS];
    private final boolean[] passed = new boolean[TurnManager.SEATS];
    private int dealingCardCount;
    private boolean ready;
    private Hand top;
    private int topSeat = -1;
    private int turnSeat = -1;
    private int wishRank;
    private int trickPoints;
    private long played;
    private boolean dragonGiveMine;

    private BotView(int seat, Phase phase, long hand) {
        this.seat = seat;
        this.phase = phase;
        this.hand = hand;
    }

    static BotView of(TichuState state, int seat) {
        Phase phase = switch (state) {
            case TichuState.Dealing __ -> Phase.DEALING;
            case TichuState.Passing __ -> Phase.PASSING;
            case TichuState.Playing __ -> Phase.PLAYING;
            case TichuState.RoundEnd __ -> Phase.ROUND_END;
        };
        BotView v = new BotView(seat, phase, HandPlanner.mask(state.players().get(seat).hand()));
        for (PlayerState p : state.players()) {
            int s = p.seat();
            v.sizes[s] = p.handSize();
            v.declarations[s] = p.declaration() == null ? TichuDeclaration.NONE : p.declaration();
            v.finishedOrder[s] = p.finishedOrder();
            v.played |= HandPlanner.mask(p.tricksWon());
        }
        switch (state) {
            case TichuState.Dealing d -> {
                v.dealingCardCount = d.phaseCardCount();
                v.ready = d.ready().contains(seat);
            }
            case TichuState.Playing pl -> {
                TrickState t = pl.trick();
                v.top = t.currentTop();
                v.topSeat = t.currentTopSeat();
                v.turnSeat = t.currentTurnSeat();
                for (int s = 0; s < TurnManager.SEATS; s++) {
                    v.passed[s] = t.passedSeats().contains(s);
                }
                v.wishRank = t.hasActiveWish() ? t.activeWish().rank() : 0;
                for (Card c : t.accumulatedCards()) {
                    v.trickPoints += c.points();
                }
                v.played |= HandPlanner.mask(t.accumulatedCards());
                v.dragonGiveMine = v.top != null && v.top.cards().size() == 1
                        && v.top.cards().get(0).is(Special.DRAGON) && v.topSeat == seat;
            }
            case TichuState.Passing __ -> { }
            case TichuState.RoundEnd __ -> { }
        }
        return v;
    }

    // ---------- 좌석 ----------

    int seat() {
        return seat;
    }

    Phase phase() {
        return phase;
    }

    int partner() {
        return TurnManager.partnerOf(seat);
    }

    int leftOpponent() {
        return (seat + 1) % TurnManager.SEATS;
    }

    int rightOpponent() {
        return (seat + 3) % TurnManager.SEATS;
    }

    boolean isOpponent(int s) {
        return s % 2 != seat % 2;
    }

    int size(int s) {
        return sizes[s];
    }

    boolean declared(int s) {
        return declarations[s] != TichuDeclaration.NONE;
    }

    boolean finished(int s) {
        return finishedOrder[s] > 0;
    }

    boolean active(int s) {
        return !finished(s);
    }

    boolean anyFinished() {
        for (int s = 0; s < TurnManager.SEATS; s++) {
            if (finished(s)) return true;
        }
        return false;
    }

    // ---------- 손패 · 나온 카드 ----------

    long hand() {
        return hand;
    }

    int handSize() {
        return Long.bitCount(hand);
    }

    /** 공개적으로 나온 카드 (모든 tricksWon + 진행 중 트릭). */
    long played() {
        return played;
    }

    /** 아직 보지 못한 카드 = 56 − 내 손 − 나온 카드. 상대·파트너 손패와 (Dealing 이면) 남은 분배 몫. */
    long unseen() {
        return ALL & ~hand & ~played;
    }

    // ---------- 단계별 ----------

    int dealingCardCount() {
        return dealingCardCount;
    }

    boolean ready() {
        return ready;
    }

    Hand top() {
        return top;
    }

    boolean isLead() {
        return top == null;
    }

    int topSeat() {
        return topSeat;
    }

    int turnSeat() {
        return turnSeat;
    }

    boolean passed(int s) {
        return passed[s];
    }

    /** 활성 소원 rank, 없으면 0. */
    int wishRank() {
        return wishRank;
    }

    int trickPoints() {
        return trickPoints;
    }

    boolean dragonGiveMine() {
        return dragonGiveMine;
    }
}
