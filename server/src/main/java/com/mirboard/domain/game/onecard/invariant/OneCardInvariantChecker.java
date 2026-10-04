package com.mirboard.domain.game.onecard.invariant;

import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.state.Elimination;
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
            return;
        }
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
            return;
        }
        if (s.turnSeat() < 0 || s.turnSeat() >= s.seatCount() || !s.alive(s.turnSeat())) {
            fail("turn seat must be a live seat: " + s.turnSeat());
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("One Card invariant violated: " + message);
    }
}
