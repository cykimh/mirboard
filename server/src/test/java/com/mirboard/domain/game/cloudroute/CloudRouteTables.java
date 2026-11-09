package com.mirboard.domain.game.cloudroute;

import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.cloudroute.state.SeatState;
import com.mirboard.domain.game.core.GameContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

/** 룰 단위 테스트용 상태 빌더와 정해 둔 주사위. */
final class CloudRouteTables {

    private CloudRouteTables() {
    }

    static GameContext context(int seats, Integer... bots) {
        return new GameContext("t", LongStream.range(0, seats).map(i -> 100 + i).boxed().toList(), 0, 0,
                Arrays.asList(bots));
    }

    /** 정해 둔 눈을 차례로 내는 주사위. 다 쓰면 실패한다. */
    static final class ScriptedDice implements CloudRouteEngine.Dice {
        private final Deque<Integer> values = new ArrayDeque<>();

        ScriptedDice(int... faces) {
            for (int face : faces) {
                values.add(face);
            }
        }

        void add(int... faces) {
            for (int face : faces) {
                values.add(face);
            }
        }

        @Override
        public int roll() {
            if (values.isEmpty()) {
                throw new AssertionError("scripted dice ran out");
            }
            return values.poll();
        }

        boolean exhausted() {
            return values.isEmpty();
        }
    }

    static CloudRouteEngine engine(int seats, ScriptedDice dice, Integer... bots) {
        return new CloudRouteEngine(context(seats, bots), new Random(1), dice);
    }

    static Builder table(int seats) {
        return new Builder(seats);
    }

    static final class Builder {
        private final int n;
        private final int[] cash;
        private final int[] pos;
        private final boolean[] trapped;
        private final int[] failed;
        private final int[] owners = new int[Board.SIZE];
        private final int[] levels = new int[Board.SIZE];
        private int festival = -1;
        private int buoy = -1;
        private List<Letter> deck = new ArrayList<>(Letter.fullDeck());
        private final List<Letter> discard = new ArrayList<>();
        private int startSeat;
        private int turn;
        private int round = 1;
        private int doubles;
        private boolean rollAgain;
        private boolean flew;
        private final List<Integer> locked = new ArrayList<>();
        private Pending pending = new Pending.AwaitRoll();
        private final List<Elimination> eliminations = new ArrayList<>();

        Builder(int seats) {
            n = seats;
            cash = new int[seats];
            pos = new int[seats];
            trapped = new boolean[seats];
            failed = new int[seats];
            Arrays.fill(cash, Economy.START_CASH);
            Arrays.fill(owners, -1);
        }

        Builder cash(int seat, int value) {
            cash[seat] = value;
            return this;
        }

        Builder at(int seat, int tile) {
            pos[seat] = tile;
            return this;
        }

        Builder own(int seat, int tile, int level) {
            owners[tile] = seat;
            levels[tile] = level;
            return this;
        }

        Builder festival(int tile) {
            festival = tile;
            return this;
        }

        Builder trapped(int seat, int failedEscapes) {
            trapped[seat] = true;
            failed[seat] = failedEscapes;
            pos[seat] = Board.WHIRLPOOL;
            return this;
        }

        /** 맨 위부터 이 순서로 놓고 나머지는 원래 순서로 뒤에 붙인다. */
        Builder deckTop(Letter... top) {
            List<Letter> rest = new ArrayList<>(deck);
            rest.removeAll(Arrays.asList(top));
            List<Letter> next = new ArrayList<>(Arrays.asList(top));
            next.addAll(rest);
            deck = next;
            return this;
        }

        /** 부표를 그 좌석에 쥐여 준다(더미에서 뺌). */
        Builder buoy(int seat) {
            deck.remove(Letter.BUOY);
            discard.remove(Letter.BUOY);
            buoy = seat;
            return this;
        }

        /** 더미를 비우고 전부 버린 더미로(부표 보유는 그대로). */
        Builder emptyDeck() {
            discard.addAll(deck);
            deck = new ArrayList<>();
            return this;
        }

        Builder start(int seat) {
            startSeat = seat;
            return this;
        }

        Builder turn(int seat) {
            turn = seat;
            return this;
        }

        Builder round(int value) {
            round = value;
            return this;
        }

        Builder doubles(int value) {
            doubles = value;
            return this;
        }

        Builder rollAgain() {
            rollAgain = true;
            return this;
        }

        Builder flew() {
            flew = true;
            return this;
        }

        Builder locked(int tile) {
            locked.add(tile);
            return this;
        }

        Builder pending(Pending value) {
            pending = value;
            return this;
        }

        Builder eliminated(int seat, Elimination.Reason reason) {
            eliminations.add(new Elimination(seat, reason));
            cash[seat] = 0;
            return this;
        }

        CloudRouteState build() {
            List<SeatState> seats = new ArrayList<>();
            for (int seat = 0; seat < n; seat++) {
                seats.add(new SeatState(cash[seat], pos[seat], trapped[seat], failed[seat]));
            }
            if (trapped[turn] && pending instanceof Pending.AwaitRoll) {
                pending = new Pending.AwaitWhirlpool(cash[turn] >= Economy.ESCAPE_FEE, buoy == turn);
            }
            return new CloudRouteState(seats, Arrays.stream(owners).boxed().toList(),
                    Arrays.stream(levels).boxed().toList(), festival, buoy, deck, discard, startSeat, turn, round,
                    Economy.roundLimit(n), doubles, rollAgain, flew, locked, List.of(), pending, eliminations, 1, null);
        }
    }
}
