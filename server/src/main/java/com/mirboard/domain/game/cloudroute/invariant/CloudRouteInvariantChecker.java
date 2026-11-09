package com.mirboard.domain.game.cloudroute.invariant;

import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.board.Tile;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.MatchResult.EndReason;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.cloudroute.state.SeatState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 구름항로 상태 불변식 (`docs/rules-cloudroute.md` §16). 시뮬레이션이 매 전이 뒤에 부른다. 돈 장부(전이 전후 현금 합과 은행 흐름)는
 * 이벤트가 필요해 시뮬레이션 테스트가 따로 본다.
 */
public final class CloudRouteInvariantChecker {

    private CloudRouteInvariantChecker() {
    }

    public static void check(CloudRouteState s) {
        int n = s.seatCount();
        if (n < 2 || n > 4) {
            fail("seat count " + n);
        }
        if (!Economy.isRoundLimit(n, s.roundLimit())) {
            fail("round limit " + s.roundLimit() + " for " + n + " seats");
        }
        if (s.round() < 1 || s.round() > s.roundLimit()) {
            fail("round " + s.round() + " outside 1.." + s.roundLimit());
        }
        if (s.startSeat() < 0 || s.startSeat() >= n) {
            fail("start seat " + s.startSeat());
        }

        // 좌석
        for (int seat = 0; seat < n; seat++) {
            SeatState ss = s.seat(seat);
            if (ss.cash() < 0) {
                fail("seat " + seat + " cash " + ss.cash());
            }
            if (ss.position() < 0 || ss.position() >= Board.SIZE) {
                fail("seat " + seat + " position " + ss.position());
            }
            if (ss.failedEscapes() < 0 || ss.failedEscapes() > Economy.MAX_FAILED_ESCAPES
                    || (ss.failedEscapes() > 0 && !ss.trapped())) {
                fail("seat " + seat + " failed escapes " + ss.failedEscapes() + " trapped=" + ss.trapped());
            }
            if (ss.trapped() && ss.position() != Board.WHIRLPOOL) {
                fail("seat " + seat + " trapped away from the whirlpool");
            }
            if (!s.alive(seat) && (ss.cash() != 0 || ss.trapped() || !s.ownedTiles(seat).isEmpty())) {
                fail("eliminated seat " + seat + " still holds something");
            }
        }

        // 칸
        for (int t = 0; t < Board.SIZE; t++) {
            int owner = s.owners().get(t);
            int level = s.levels().get(t);
            if (level < 0 || level > Economy.MAX_LEVEL) {
                fail("tile " + t + " level " + level);
            }
            if (owner >= 0 && (!Board.isLand(t) || owner >= n || !s.alive(owner))) {
                fail("tile " + t + " owned by " + owner);
            }
            if (owner < -1) {
                fail("tile " + t + " owner " + owner);
            }
            if (owner < 0 && level != 0) {
                fail("unowned tile " + t + " at level " + level);
            }
        }
        if (s.festivalTile() != -1 && (s.festivalTile() < 0 || s.festivalTile() >= Board.SIZE
                || s.owners().get(s.festivalTile()) < 0)) {
            fail("festival on unowned tile " + s.festivalTile());
        }

        // 편지
        List<Letter> all = new ArrayList<>(s.letterDeck());
        all.addAll(s.letterDiscard());
        if (s.buoyHolder() >= 0) {
            if (s.buoyHolder() >= n || !s.alive(s.buoyHolder())) {
                fail("buoy held by " + s.buoyHolder());
            }
            all.add(Letter.BUOY);
        } else if (s.buoyHolder() != -1) {
            fail("buoy holder " + s.buoyHolder());
        }
        if (all.size() != Letter.values().length || !EnumSet.copyOf(all).equals(EnumSet.allOf(Letter.class))) {
            fail("letters not conserved: deck " + s.letterDeck() + " discard " + s.letterDiscard()
                    + " buoy " + s.buoyHolder());
        }

        if (s.ended()) {
            checkEnded(s);
        } else {
            checkOpen(s);
        }
    }

    private static void checkEnded(CloudRouteState s) {
        if (s.turnSeat() != -1 || s.pending() != null) {
            fail("ended match still has a turn " + s.turnSeat() + " / " + s.pending());
        }
        int alive = s.aliveSeats().size();
        EndReason reason = s.result().reason();
        switch (reason) {
            case LAST_STANDING -> {
                if (alive != 1) {
                    fail("LAST_STANDING with " + alive + " alive");
                }
            }
            case NO_HUMANS -> {
                if (alive < 2) {
                    fail("NO_HUMANS with " + alive + " alive (LAST_STANDING comes first)");
                }
            }
            case ROUND_LIMIT -> {
                if (alive < 2 || s.round() != s.roundLimit()) {
                    fail("ROUND_LIMIT at round " + s.round() + " with " + alive + " alive");
                }
            }
        }
        if (s.result().standings().size() != s.seatCount()) {
            fail("standings " + s.result().standings());
        }
    }

    private static void checkOpen(CloudRouteState s) {
        int seat = s.turnSeat();
        if (seat < 0 || seat >= s.seatCount() || !s.alive(seat)) {
            fail("turn seat " + seat + " is not alive");
        }
        if (s.aliveSeats().size() < 2) {
            fail("open match with " + s.aliveSeats().size() + " alive");
        }
        if (s.pending() == null) {
            fail("open match without a pending decision");
        }
        if (s.doublesThisTurn() < 0 || s.doublesThisTurn() > 2) {
            fail("doubles this turn " + s.doublesThisTurn());
        }
        SeatState me = s.seat(seat);
        if (s.rollAgain() && me.trapped()) {
            fail("roll again while trapped");
        }
        if (me.trapped() != (s.pending() instanceof Pending.AwaitWhirlpool)) {
            fail("trapped=" + me.trapped() + " but pending " + s.pending());
        }
        int cash = me.cash();
        switch (s.pending()) {
            case Pending.AwaitRoll __ -> {
            }
            case Pending.AwaitWhirlpool w -> {
                if (w.canPay() != (cash >= Economy.ESCAPE_FEE) || w.canUseBuoy() != (s.buoyHolder() == seat)) {
                    fail("whirlpool options " + w + " cash " + cash + " buoy " + s.buoyHolder());
                }
            }
            case Pending.AwaitPurchase p -> {
                Tile tile = Board.tile(p.tile());
                if (!Board.isLand(p.tile()) || s.owners().get(p.tile()) != -1 || p.maxLevel() < 0
                        || p.maxLevel() > Economy.MAX_PURCHASE_LEVEL
                        || cash < tile.price() + p.maxLevel() * tile.buildCost() || s.seat(seat).position() != p.tile()) {
                    fail("purchase " + p + " cash " + cash);
                }
            }
            case Pending.AwaitBuild b -> {
                Tile tile = Board.tile(b.tile());
                int level = s.levels().get(b.tile());
                if (s.owners().get(b.tile()) != seat || b.minLevel() != level + 1 || b.maxLevel() < b.minLevel()
                        || b.maxLevel() > Economy.MAX_LEVEL || s.lockedTiles().contains(b.tile())
                        || cash < (b.maxLevel() - level) * tile.buildCost()) {
                    fail("build " + b + " cash " + cash);
                }
            }
            case Pending.AwaitTakeover t -> {
                int level = s.levels().get(t.tile());
                if (s.owners().get(t.tile()) != t.owner() || t.owner() == seat || level >= Economy.MAX_LEVEL
                        || t.price() != Economy.takeoverPrice(Board.tile(t.tile()), level) || cash < t.price()) {
                    fail("takeover " + t + " cash " + cash);
                }
            }
            case Pending.AwaitFestival f -> {
                if (f.candidates().isEmpty() || f.candidates().stream()
                        .anyMatch(t -> s.owners().get(t) != seat || t == s.festivalTile())) {
                    fail("festival candidates " + f);
                }
            }
            case Pending.AwaitFlight f -> {
                if (s.flewThisTurn() || cash < Economy.FLIGHT_FEE || f.candidates().contains(Board.STATION)
                        || f.candidates().size() != Board.SIZE - 1) {
                    fail("flight " + f + " cash " + cash + " flew " + s.flewThisTurn());
                }
            }
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("Cloud Route invariant: " + message);
    }
}
