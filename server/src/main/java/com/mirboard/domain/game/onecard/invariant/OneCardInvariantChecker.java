package com.mirboard.domain.game.onecard.invariant;

import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 원카드 상태 불변식 (`docs/rules-onecard.md` §14). 시뮬레이션이 매 전이 뒤 부르고, 깨지면
 * {@link IllegalStateException} 을 던진다.
 */
public final class OneCardInvariantChecker {

    private OneCardInvariantChecker() {
    }

    public static void check(OneCardState s) {
        checkCardConservation(s);
        if (s.discardPile().isEmpty()) {
            fail("discard pile has no top card");
        }
        if (s.direction() != 1 && s.direction() != -1) {
            fail("direction must be ±1: " + s.direction());
        }
        for (Elimination e : s.eliminations()) {
            if (!s.hands().get(e.seat()).isEmpty()) {
                fail("eliminated seat " + e.seat() + " still holds cards");
            }
        }
        for (int seat : s.aliveSeats()) {
            if (s.hands().get(seat).size() >= OneCardEngine.BANKRUPTCY_HAND_SIZE) {
                fail("seat " + seat + " should have gone bankrupt");
            }
        }
        if (s.attackStack() < 0) {
            fail("negative attack stack");
        }
        if (s.attackStack() > 0 && !s.topCard().isAttack()) {
            fail("attack pending but the top card is not an attack card");
        }
        if (s.declaredSuit() != null && !s.topCard().isSuitChange()) {
            fail("declared suit without a 7 on top");
        }
        checkTurn(s);
    }

    /** 54장이 더미·손패에 정확히 한 번씩 (§14). 탈락자 손패는 더미로 갔으므로 0이다. */
    private static void checkCardConservation(OneCardState s) {
        List<PlayingCard> all = new ArrayList<>(s.drawPile());
        all.addAll(s.discardPile());
        s.hands().forEach(all::addAll);
        if (all.size() != Deck.SIZE || !new HashSet<>(all).equals(new HashSet<>(Deck.all()))) {
            fail("card conservation broken: " + all.size() + " cards");
        }
    }

    private static void checkTurn(OneCardState s) {
        RaceWindow race = s.race();
        if (s.ended()) {
            if (s.turnSeat() != -1 || race != null) {
                fail("ended match still has a turn or a race");
            }
            if (s.result().standings().size() != s.seatCount()) {
                fail("standings must cover every seat");
            }
            checkEnding(s);
        } else {
            if (race != null) {
                if (s.turnSeat() != -1) {
                    fail("nobody has the turn while a race is open");
                }
                if (!s.alive(race.ownerSeat()) || s.hands().get(race.ownerSeat()).size() != 1) {
                    fail("race owner must be alive with exactly one card");
                }
                if (race.nextSeat() < 0 || race.nextSeat() >= s.seatCount()) {
                    fail("race has no next seat");
                }
            } else if (s.turnSeat() < 0 || s.turnSeat() >= s.seatCount() || !s.alive(s.turnSeat())) {
                fail("turn seat must be a live seat: " + s.turnSeat());
            }
            checkStillOpen(s);
        }
        if (s.turnCount() > OneCardEngine.TURN_LIMIT) {
            fail("turn count " + s.turnCount() + " is above the limit " + OneCardEngine.TURN_LIMIT);
        }
    }

    /**
     * 끝나지 않은 판은 더 이어질 수 있는 상태여야 한다 (§11.1, §11.3). 엔진은 전이마다 종료를 판정하므로, 아래 중
     * 하나라도 걸리면 종료 판정이 빠진 것이다.
     */
    private static void checkStillOpen(OneCardState s) {
        int alive = s.aliveSeats().size();
        if (alive < 2) {
            fail("an open match needs at least two live seats: " + alive);
        }
        if (s.passStreak() >= alive) {
            fail("pass streak " + s.passStreak() + " should have ended the match (" + alive + " live seats)");
        }
        if (s.turnCount() >= OneCardEngine.TURN_LIMIT) {
            fail("turn limit reached but the match is still open");
        }
        for (int seat : s.aliveSeats()) {
            if (s.hands().get(seat).isEmpty()) {
                fail("live seat " + seat + " has no cards but the match is still open");
            }
        }
        if (s.race() != null && !s.alive(s.race().nextSeat())) {
            fail("race reserves the eliminated seat " + s.race().nextSeat());
        }
    }

    /** 끝난 판의 사유는 상태가 실제로 말하는 것과 맞아야 한다 (§11.1). */
    private static void checkEnding(OneCardState s) {
        int alive = s.aliveSeats().size();
        EndReason reason = s.result().reason();
        boolean emptyHandAlive = s.aliveSeats().stream().anyMatch(seat -> s.hands().get(seat).isEmpty());
        if (emptyHandAlive && reason != EndReason.FINISHED) {
            fail("a live seat has an empty hand but the match ended as " + reason);
        }
        if (!emptyHandAlive && reason == EndReason.FINISHED) {
            fail("FINISHED but no live seat has an empty hand");
        }
        switch (reason) {
            case LAST_STANDING -> {
                if (alive != 1) {
                    fail("LAST_STANDING needs exactly one live seat: " + alive);
                }
            }
            case NO_HUMANS, STALEMATE -> {
                if (alive < 2) {
                    fail(reason + " needs at least two live seats: " + alive);
                }
            }
            case FINISHED -> {
            }
        }
        if (reason == EndReason.STALEMATE && s.passStreak() < alive && s.turnCount() < OneCardEngine.TURN_LIMIT) {
            fail("STALEMATE without a full pass streak or the turn limit");
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("One Card invariant violated: " + message);
    }
}
