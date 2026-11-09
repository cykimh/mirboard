package com.mirboard.domain.game.cloudroute;

import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.action.CloudRouteActionRejectedException;
import com.mirboard.domain.game.cloudroute.action.RejectionReason;
import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.board.Tile;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.AssetsLiquidated.Sale;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.LeaveMethod;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.MoneyReason;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.MoveKind;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.TransferReason;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.MatchResult;
import com.mirboard.domain.game.cloudroute.state.MatchResult.EndReason;
import com.mirboard.domain.game.cloudroute.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.cloudroute.state.MatchResult.Standing;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.cloudroute.state.SeatState;
import com.mirboard.domain.game.core.GameContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * 구름항로 <b>순수 룰 엔진</b> (`docs/rules-cloudroute.md`).
 *
 * <p>Spring·Redis·시계를 모른다. 난수는 주입받는다 — 주사위는 {@link Dice}, 편지 섞기와 첫 차례는 {@link Random}. 테스트는 주사위
 * 열을 정해 넣는다. 포트 어댑터·저장·봇 정책·기록은 S2 범위다(원카드 {@code OneCardEngine} + {@code OneCardGameEngine} 과 같은 2계층).
 *
 * <p>상태는 불변 레코드다. 한 전이가 여러 필드를 함께 바꾸므로 엔진 안에서만 가변 사본({@link Table})을 만들어 고친 뒤 새 레코드로
 * 얼린다. 차례 안의 결정은 상태의 {@link Pending} 하나로 기다리고, {@link #apply} 한 번이 결정 하나를 풀고 그 뒤 결정이 없는 처리
 * (이동·통행료·청산·다음 좌석)까지 한 번에 진행한다.
 */
public final class CloudRouteEngine {

    /** 6면 주사위 하나. 1~6 을 돌려준다. */
    @FunctionalInterface
    public interface Dice {
        int roll();

        static Dice of(Random random) {
            return () -> 1 + random.nextInt(6);
        }
    }

    private final GameContext context;
    private final Random rng;
    private final Dice dice;

    public CloudRouteEngine(GameContext context, Random rng, Dice dice) {
        this.context = context;
        this.rng = rng;
        this.dice = dice;
    }

    public CloudRouteEngine(GameContext context, Random rng) {
        this(context, rng, Dice.of(rng));
    }

    public GameContext context() {
        return context;
    }

    /** 전이 결과 — 새 상태 + 발행할 이벤트. */
    public record Result(CloudRouteState newState, List<CloudRouteEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    /** {@link #desert} 결과. */
    public record Desertion(CloudRouteState newState, Outcome outcome, List<CloudRouteEvent> events) {

        public enum Outcome {
            /** 이미 끝났거나 이미 탈락한 좌석 — 상태 무변경, 이벤트 0건. */
            NOT_APPLICABLE,
            /** 남은 사람끼리 계속. */
            CONTINUED,
            /** 1명만 남았거나 살아 있는 사람이 없어 끝났다(§12.1). */
            MATCH_ENDED
        }

        public Desertion {
            events = List.copyOf(events);
        }
    }

    // ---------- 시작 (§3) ----------

    public Result startMatch() {
        List<Letter> deck = new ArrayList<>(Letter.fullDeck());
        Collections.shuffle(deck, rng);
        return startMatch(rng.nextInt(context.seatCount()), deck);
    }

    /** 첫 차례 좌석과 편지 더미 순서를 정해 시작한다(테스트용 — 실제 판은 {@link #startMatch()}). */
    public Result startMatch(int firstSeat, List<Letter> deckOrder) {
        int seatCount = context.seatCount();
        // D-215 — 방장이 고른 판 길이. 상한은 시작 때 상태에 박혀 판 도중 바뀌지 않는다.
        int roundLimit = Economy.roundLimit(seatCount,
                Economy.MatchLength.of(context.option(com.mirboard.domain.game.core.RoomOption.MATCH_LENGTH)));
        if (firstSeat < 0 || firstSeat >= seatCount) {
            throw new IllegalArgumentException("no such seat: " + firstSeat);
        }
        if (deckOrder.size() != Letter.values().length || !EnumSet.copyOf(deckOrder).equals(EnumSet.allOf(Letter.class))) {
            throw new IllegalArgumentException("deck must hold each letter once: " + deckOrder);
        }
        Table t = new Table(seatCount, firstSeat, deckOrder, roundLimit);
        t.beginTurn(firstSeat);
        return new Result(t.freeze(), t.events);
    }

    // ---------- 액션 (§4 ~ §9) ----------

    /** @throws CloudRouteActionRejectedException 룰 위반 */
    public Result apply(CloudRouteState state, int seat, CloudRouteAction action) {
        if (state.ended()) {
            throw rejected(RejectionReason.MATCH_OVER);
        }
        if (seat < 0 || seat >= state.seatCount()) {
            throw new IllegalArgumentException("no such seat: " + seat);
        }
        if (!state.alive(seat)) {
            throw rejected(RejectionReason.PLAYER_ELIMINATED);
        }
        if (seat != state.turnSeat()) {
            throw rejected(RejectionReason.NOT_YOUR_TURN);
        }
        Table t = new Table(state);
        Pending pending = t.pending;
        switch (action) {
            case CloudRouteAction.Roll __ -> {
                if (pending instanceof Pending.AwaitRoll) {
                    t.pending = null;
                    t.roll();
                } else if (pending instanceof Pending.AwaitWhirlpool) {
                    t.pending = null;
                    t.rollInWhirlpool();
                } else {
                    throw rejected(RejectionReason.WRONG_DECISION);
                }
            }
            case CloudRouteAction.PayEscape __ -> {
                require(pending instanceof Pending.AwaitWhirlpool);
                if (t.cash[seat] < Economy.ESCAPE_FEE) {
                    throw rejected(RejectionReason.INSUFFICIENT_FUNDS);
                }
                t.pending = null;
                t.cash[seat] -= Economy.ESCAPE_FEE;
                t.emit(new CloudRouteEvent.MoneyChanged(seat, -Economy.ESCAPE_FEE, MoneyReason.ESCAPE, t.cash[seat]));
                t.leave(seat, LeaveMethod.PAID);
                t.setPending(new Pending.AwaitRoll());
            }
            case CloudRouteAction.UseBuoy __ -> {
                require(pending instanceof Pending.AwaitWhirlpool);
                if (t.buoyHolder != seat) {
                    throw rejected(RejectionReason.NO_BUOY);
                }
                t.pending = null;
                t.buoyHolder = -1;
                t.discard.add(Letter.BUOY);
                t.leave(seat, LeaveMethod.BUOY);
                t.setPending(new Pending.AwaitRoll());
            }
            case CloudRouteAction.Buy buy -> {
                if (!(pending instanceof Pending.AwaitPurchase purchase)) {
                    throw rejected(RejectionReason.WRONG_DECISION);
                }
                if (buy.buildTo() < 0 || buy.buildTo() > purchase.maxLevel()) {
                    throw rejected(RejectionReason.INVALID_LEVEL);
                }
                t.pending = null;
                t.buy(purchase.tile(), buy.buildTo());
            }
            case CloudRouteAction.Decline __ -> {
                require(pending instanceof Pending.AwaitPurchase);
                t.pending = null;
            }
            case CloudRouteAction.Build build -> {
                if (!(pending instanceof Pending.AwaitBuild b)) {
                    throw rejected(RejectionReason.WRONG_DECISION);
                }
                if (build.buildTo() < b.minLevel() || build.buildTo() > b.maxLevel()) {
                    throw rejected(RejectionReason.INVALID_LEVEL);
                }
                t.pending = null;
                t.build(b.tile(), build.buildTo());
            }
            case CloudRouteAction.Skip __ -> {
                require(pending instanceof Pending.AwaitBuild || pending instanceof Pending.AwaitTakeover
                        || pending instanceof Pending.AwaitFestival || pending instanceof Pending.AwaitFlight);
                t.pending = null;
            }
            case CloudRouteAction.Takeover __ -> {
                if (!(pending instanceof Pending.AwaitTakeover takeover)) {
                    throw rejected(RejectionReason.WRONG_DECISION);
                }
                t.pending = null;
                t.takeover(takeover);
            }
            case CloudRouteAction.HostFestival host -> {
                if (!(pending instanceof Pending.AwaitFestival festival)) {
                    throw rejected(RejectionReason.WRONG_DECISION);
                }
                if (!festival.candidates().contains(host.tile())) {
                    throw rejected(RejectionReason.INVALID_TILE);
                }
                t.pending = null;
                t.hostFestival(host.tile());
            }
            case CloudRouteAction.Fly fly -> {
                if (!(pending instanceof Pending.AwaitFlight flight)) {
                    throw rejected(RejectionReason.WRONG_DECISION);
                }
                if (!flight.candidates().contains(fly.tile())) {
                    throw rejected(RejectionReason.INVALID_TILE);
                }
                t.pending = null;
                t.fly(fly.tile(), flight.free());
            }
            case CloudRouteAction.GiftBuild gift -> {
                if (!(pending instanceof Pending.AwaitGift g)) {
                    throw rejected(RejectionReason.WRONG_DECISION);
                }
                if (!g.candidates().contains(gift.tile())) {
                    throw rejected(RejectionReason.INVALID_TILE);
                }
                t.pending = null;
                t.giftBuild(gift.tile());
            }
        }
        t.proceed();
        t.version++;
        return new Result(t.freeze(), t.events);
    }

    // ---------- 탈주 (§11) ----------

    public Desertion desert(CloudRouteState state, int seat) {
        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
            return new Desertion(state, Desertion.Outcome.NOT_APPLICABLE, List.of());
        }
        Table t = new Table(state);
        t.eliminate(seat, Elimination.Reason.DESERTED, -1);
        t.proceed();
        t.version++;
        CloudRouteState next = t.freeze();
        return new Desertion(next, next.ended() ? Desertion.Outcome.MATCH_ENDED : Desertion.Outcome.CONTINUED, t.events);
    }

    // ---------- 질의 (포트 S2 가 쓴다) ----------

    /** 지금 행동할 좌석 — 늘 차례 좌석 하나, 끝났으면 빈 목록. */
    public List<Integer> pendingSeats(CloudRouteState state) {
        return state.ended() ? List.of() : List.of(state.turnSeat());
    }

    /** 그 좌석이 지금 할 수 있는 행동 전부. 차례가 아니면 빈 목록. */
    public List<CloudRouteAction> legalActions(CloudRouteState state, int seat) {
        if (state.ended() || seat != state.turnSeat() || !state.alive(seat)) {
            return List.of();
        }
        List<CloudRouteAction> legal = new ArrayList<>();
        switch (state.pending()) {
            case Pending.AwaitRoll __ -> legal.add(new CloudRouteAction.Roll());
            case Pending.AwaitWhirlpool w -> {
                legal.add(new CloudRouteAction.Roll());
                if (w.canPay()) {
                    legal.add(new CloudRouteAction.PayEscape());
                }
                if (w.canUseBuoy()) {
                    legal.add(new CloudRouteAction.UseBuoy());
                }
            }
            case Pending.AwaitPurchase p -> {
                for (int level = 0; level <= p.maxLevel(); level++) {
                    legal.add(new CloudRouteAction.Buy(level));
                }
                legal.add(new CloudRouteAction.Decline());
            }
            case Pending.AwaitBuild b -> {
                for (int level = b.minLevel(); level <= b.maxLevel(); level++) {
                    legal.add(new CloudRouteAction.Build(level));
                }
                legal.add(new CloudRouteAction.Skip());
            }
            case Pending.AwaitTakeover __ -> {
                legal.add(new CloudRouteAction.Takeover());
                legal.add(new CloudRouteAction.Skip());
            }
            case Pending.AwaitFestival f -> {
                f.candidates().forEach(tile -> legal.add(new CloudRouteAction.HostFestival(tile)));
                legal.add(new CloudRouteAction.Skip());
            }
            case Pending.AwaitFlight f -> {
                f.candidates().forEach(tile -> legal.add(new CloudRouteAction.Fly(tile)));
                legal.add(new CloudRouteAction.Skip());
            }
            case Pending.AwaitGift g -> g.candidates().forEach(tile -> legal.add(new CloudRouteAction.GiftBuild(tile)));
        }
        return legal;
    }

    /** 시간 초과 때 서버가 대신 하는 행동(§8) — "아무것도 하지 않는" 쪽. 봇 정책과 섞지 않는다. */
    public CloudRouteAction timeoutAction(CloudRouteState state, int seat) {
        if (state.ended() || seat != state.turnSeat()) {
            throw new IllegalStateException("seat " + seat + " is not waiting for a decision");
        }
        return switch (state.pending()) {
            case Pending.AwaitRoll __ -> new CloudRouteAction.Roll();
            case Pending.AwaitWhirlpool __ -> new CloudRouteAction.Roll();
            case Pending.AwaitPurchase __ -> new CloudRouteAction.Decline();
            case Pending.AwaitBuild __ -> new CloudRouteAction.Skip();
            case Pending.AwaitTakeover __ -> new CloudRouteAction.Skip();
            case Pending.AwaitFestival __ -> new CloudRouteAction.Skip();
            case Pending.AwaitFlight __ -> new CloudRouteAction.Skip();
            // 선물은 건너뛸 수 없다 — 시간 초과면 가장 싼 후보(같으면 낮은 칸, 결정적).
            case Pending.AwaitGift g -> new CloudRouteAction.GiftBuild(g.candidates().stream()
                    .min(java.util.Comparator.<Integer>comparingInt(tile -> Board.tile(tile).price())
                            .thenComparing(java.util.Comparator.naturalOrder()))
                    .orElseThrow());
        };
    }

    // ---------- 내부 ----------

    private static void require(boolean condition) {
        if (!condition) {
            throw rejected(RejectionReason.WRONG_DECISION);
        }
    }

    private static CloudRouteActionRejectedException rejected(RejectionReason reason) {
        return new CloudRouteActionRejectedException(reason);
    }

    private record Payment(int paid, boolean bankrupt) {
    }

    /** 한 전이 동안만 쓰는 가변 사본. 바깥에는 {@link #freeze()} 한 새 상태만 나간다. */
    private final class Table {

        final int n;
        final int[] cash;
        final int[] pos;
        final boolean[] trapped;
        final int[] failed;
        final int[] owner = new int[Board.SIZE];
        final int[] level = new int[Board.SIZE];
        int festival;
        int buoyHolder;
        final ArrayList<Letter> deck;
        final ArrayList<Letter> discard;
        final int startSeat;
        int turnSeat;
        int round;
        final int roundLimit;
        int doubles;
        boolean rollAgain;
        boolean flew;
        final TreeSet<Integer> locked;
        List<Integer> lastDice;
        Pending pending;
        final ArrayList<Elimination> eliminations;
        int version;
        MatchResult result;
        final List<CloudRouteEvent> events = new ArrayList<>();

        Table(int seatCount, int firstSeat, List<Letter> deckOrder, int roundLimit) {
            n = seatCount;
            cash = new int[n];
            pos = new int[n];
            trapped = new boolean[n];
            failed = new int[n];
            java.util.Arrays.fill(cash, Economy.START_CASH);
            java.util.Arrays.fill(owner, -1);
            festival = -1;
            buoyHolder = -1;
            deck = new ArrayList<>(deckOrder);
            discard = new ArrayList<>();
            startSeat = firstSeat;
            turnSeat = firstSeat;
            round = 1;
            this.roundLimit = roundLimit;
            locked = new TreeSet<>();
            lastDice = List.of();
            eliminations = new ArrayList<>();
            version = 1;
        }

        Table(CloudRouteState s) {
            n = s.seatCount();
            cash = new int[n];
            pos = new int[n];
            trapped = new boolean[n];
            failed = new int[n];
            for (int seat = 0; seat < n; seat++) {
                SeatState ss = s.seat(seat);
                cash[seat] = ss.cash();
                pos[seat] = ss.position();
                trapped[seat] = ss.trapped();
                failed[seat] = ss.failedEscapes();
            }
            for (int tile = 0; tile < Board.SIZE; tile++) {
                owner[tile] = s.owners().get(tile);
                level[tile] = s.levels().get(tile);
            }
            festival = s.festivalTile();
            buoyHolder = s.buoyHolder();
            deck = new ArrayList<>(s.letterDeck());
            discard = new ArrayList<>(s.letterDiscard());
            startSeat = s.startSeat();
            turnSeat = s.turnSeat();
            round = s.round();
            roundLimit = s.roundLimit();
            doubles = s.doublesThisTurn();
            rollAgain = s.rollAgain();
            flew = s.flewThisTurn();
            locked = new TreeSet<>(s.lockedTiles());
            lastDice = s.lastDice();
            pending = s.pending();
            eliminations = new ArrayList<>(s.eliminations());
            version = s.version();
            result = s.result();
        }

        CloudRouteState freeze() {
            List<SeatState> seats = IntStream.range(0, n)
                    .mapToObj(seat -> new SeatState(cash[seat], pos[seat], trapped[seat], failed[seat])).toList();
            return new CloudRouteState(seats, IntStream.of(owner).boxed().toList(), IntStream.of(level).boxed().toList(),
                    festival, buoyHolder, deck, discard, startSeat, turnSeat, round, roundLimit, doubles, rollAgain, flew,
                    List.copyOf(locked), lastDice, pending, eliminations, version, result);
        }

        void emit(CloudRouteEvent event) {
            events.add(event);
        }

        boolean alive(int seat) {
            return eliminations.stream().noneMatch(e -> e.seat() == seat);
        }

        List<Integer> aliveSeats() {
            return IntStream.range(0, n).filter(this::alive).boxed().toList();
        }

        void setPending(Pending next) {
            pending = next;
            emit(new CloudRouteEvent.DecisionRequired(turnSeat, next));
        }

        // ----- 차례 (§4) -----

        void beginTurn(int seat) {
            doubles = 0;
            flew = false;
            locked.clear();
            rollAgain = false;
            emit(new CloudRouteEvent.TurnChanged(seat, round));
            if (trapped[seat]) {
                if (failed[seat] >= Economy.MAX_FAILED_ESCAPES) {
                    leave(seat, LeaveMethod.SERVED);
                    setPending(new Pending.AwaitRoll());
                } else {
                    setPending(new Pending.AwaitWhirlpool(cash[seat] >= Economy.ESCAPE_FEE, buoyHolder == seat));
                }
            } else {
                setPending(new Pending.AwaitRoll());
            }
        }

        /** 결정이 풀린 뒤 — 새 결정이 없으면 더블이면 한 번 더, 아니면 차례를 넘긴다. */
        void proceed() {
            if (result != null || pending != null) {
                return;
            }
            if (alive(turnSeat) && rollAgain && !trapped[turnSeat]) {
                setPending(new Pending.AwaitRoll());
                return;
            }
            endTurn();
        }

        /** §4.3 — 다음 살아 있는 좌석, 한 바퀴 돌았으면 라운드 +1, 상한 라운드가 끝났으면 종료. */
        void endTurn() {
            int current = turnSeat;
            int next = current;
            do {
                next = (next + 1) % n;
            } while (!alive(next));
            if (order(next) <= order(current)) {
                if (round >= roundLimit) {
                    finish(EndReason.ROUND_LIMIT);
                    return;
                }
                round++;
            }
            turnSeat = next;
            beginTurn(next);
        }

        int order(int seat) {
            return Math.floorMod(seat - startSeat, n);
        }

        int die() {
            int value = dice.roll();
            if (value < 1 || value > 6) {
                throw new IllegalStateException("a die rolled " + value);
            }
            return value;
        }

        void roll() {
            int seat = turnSeat;
            int d1 = die();
            int d2 = die();
            boolean isDouble = d1 == d2;
            rollAgain = false;
            if (isDouble) {
                doubles++;
            }
            lastDice = List.of(d1, d2);
            emit(new CloudRouteEvent.DiceRolled(seat, d1, d2, isDouble, doubles));
            if (isDouble && doubles >= 3) {
                sendToWhirlpool(seat);
                return;
            }
            rollAgain = isDouble;
            moveForward(seat, d1 + d2, MoveKind.ROLL);
            land(seat, pos[seat]);
        }

        void rollInWhirlpool() {
            int seat = turnSeat;
            int d1 = die();
            int d2 = die();
            boolean isDouble = d1 == d2;
            rollAgain = false;
            lastDice = List.of(d1, d2);
            emit(new CloudRouteEvent.DiceRolled(seat, d1, d2, isDouble, 0));
            if (isDouble) {
                leave(seat, LeaveMethod.DOUBLES);
                moveForward(seat, d1 + d2, MoveKind.ROLL);
                land(seat, pos[seat]);
            } else {
                failed[seat]++;
                emit(new CloudRouteEvent.EscapeFailed(seat, failed[seat]));
            }
        }

        // ----- 이동 (§5) -----

        void moveForward(int seat, int steps, MoveKind kind) {
            int from = pos[seat];
            int to = Board.forward(from, steps);
            int salary = from + steps >= Board.SIZE ? Economy.SALARY : 0;
            cash[seat] += salary;
            pos[seat] = to;
            emit(new CloudRouteEvent.TokenMoved(seat, from, to, kind, salary, cash[seat]));
        }

        void moveBack(int seat, int steps) {
            int from = pos[seat];
            int to = Board.forward(from, -steps);
            pos[seat] = to;
            emit(new CloudRouteEvent.TokenMoved(seat, from, to, MoveKind.LETTER, 0, cash[seat]));
        }

        void sendToWhirlpool(int seat) {
            int from = pos[seat];
            pos[seat] = Board.WHIRLPOOL;
            emit(new CloudRouteEvent.TokenMoved(seat, from, Board.WHIRLPOOL, MoveKind.WHIRLPOOL, 0, cash[seat]));
            trap(seat);
        }

        void trap(int seat) {
            trapped[seat] = true;
            failed[seat] = 0;
            if (seat == turnSeat) {
                rollAgain = false;
            }
            emit(new CloudRouteEvent.WhirlpoolEntered(seat));
        }

        void leave(int seat, LeaveMethod method) {
            trapped[seat] = false;
            failed[seat] = 0;
            emit(new CloudRouteEvent.WhirlpoolLeft(seat, method));
        }

        // ----- 칸 처리 (§6) -----

        void land(int seat, int tileId) {
            Tile tile = Board.tile(tileId);
            switch (tile.kind()) {
                case START -> {
                }
                case LAND -> landOnLand(seat, tile);
                case LETTER -> drawLetter(seat);
                case WHIRLPOOL -> trap(seat);
                case CUSTOMS -> payBank(seat, Economy.CUSTOMS_FEE, MoneyReason.CUSTOMS);
                case FESTIVAL -> {
                    List<Integer> candidates = IntStream.range(0, Board.SIZE)
                            .filter(t -> owner[t] == seat && t != festival).boxed().toList();
                    if (!candidates.isEmpty()) {
                        setPending(new Pending.AwaitFestival(candidates));
                    }
                }
                case STATION -> {
                    if (!flew && cash[seat] >= Economy.FLIGHT_FEE) {
                        List<Integer> candidates = IntStream.range(0, Board.SIZE)
                                .filter(t -> t != Board.STATION).boxed().toList();
                        setPending(new Pending.AwaitFlight(candidates));
                    }
                }
            }
        }

        void landOnLand(int seat, Tile tile) {
            int id = tile.id();
            int o = owner[id];
            if (o < 0) {
                if (cash[seat] >= tile.price()) {
                    int maxLevel = Math.min(Economy.MAX_PURCHASE_LEVEL, (cash[seat] - tile.price()) / tile.buildCost());
                    setPending(new Pending.AwaitPurchase(id, maxLevel));
                }
            } else if (o == seat) {
                if (level[id] < Economy.MAX_LEVEL && !locked.contains(id) && cash[seat] >= tile.buildCost()) {
                    int maxLevel = Math.min(Economy.MAX_LEVEL, level[id] + cash[seat] / tile.buildCost());
                    setPending(new Pending.AwaitBuild(id, level[id] + 1, maxLevel));
                }
            } else {
                payToll(seat, o, tile);
            }
        }

        void payToll(int seat, int o, Tile tile) {
            int id = tile.id();
            boolean monopoly = owner[Board.partner(id)] == o;
            boolean fest = festival == id;
            int amount = Economy.toll(tile, level[id], monopoly, fest);
            Payment payment = charge(seat, amount);
            cash[o] += payment.paid();
            emit(new CloudRouteEvent.TollPaid(seat, o, id, payment.paid(), monopoly, fest, cash[seat], cash[o]));
            if (payment.bankrupt()) {
                eliminate(seat, Elimination.Reason.BANKRUPT, o);
                return;
            }
            if (level[id] < Economy.MAX_LEVEL) {
                int price = Economy.takeoverPrice(tile, level[id]);
                if (cash[seat] >= price) {
                    setPending(new Pending.AwaitTakeover(id, o, price));
                }
            }
        }

        void buy(int tileId, int buildTo) {
            int seat = turnSeat;
            Tile tile = Board.tile(tileId);
            int cost = tile.price() + buildTo * tile.buildCost();
            if (owner[tileId] >= 0 || cash[seat] < cost) {
                throw rejected(RejectionReason.INSUFFICIENT_FUNDS);
            }
            cash[seat] -= cost;
            owner[tileId] = seat;
            level[tileId] = buildTo;
            locked.add(tileId);
            emit(new CloudRouteEvent.LandBought(seat, tileId, buildTo, cost, cash[seat]));
        }

        void build(int tileId, int buildTo) {
            int seat = turnSeat;
            Tile tile = Board.tile(tileId);
            int cost = (buildTo - level[tileId]) * tile.buildCost();
            if (owner[tileId] != seat || cash[seat] < cost) {
                throw rejected(RejectionReason.INSUFFICIENT_FUNDS);
            }
            cash[seat] -= cost;
            level[tileId] = buildTo;
            emit(new CloudRouteEvent.Built(seat, tileId, buildTo, cost, cash[seat]));
        }

        void takeover(Pending.AwaitTakeover offer) {
            int seat = turnSeat;
            int tileId = offer.tile();
            int seller = owner[tileId];
            if (seller != offer.owner() || level[tileId] >= Economy.MAX_LEVEL) {
                throw rejected(RejectionReason.WRONG_DECISION);
            }
            if (cash[seat] < offer.price()) {
                throw rejected(RejectionReason.INSUFFICIENT_FUNDS);
            }
            cash[seat] -= offer.price();
            cash[seller] += offer.price();
            owner[tileId] = seat;
            locked.add(tileId);
            emit(new CloudRouteEvent.TakenOver(seat, seller, tileId, offer.price(), cash[seat], cash[seller]));
            if (festival == tileId) {
                festival = -1;
                emit(new CloudRouteEvent.FestivalCleared(tileId));
            }
        }

        void hostFestival(int tileId) {
            festival = tileId;
            emit(new CloudRouteEvent.FestivalHosted(turnSeat, tileId));
        }

        void fly(int tileId, boolean free) {
            int seat = turnSeat;
            if (!free) {
                if (cash[seat] < Economy.FLIGHT_FEE) {
                    throw rejected(RejectionReason.INSUFFICIENT_FUNDS);
                }
                cash[seat] -= Economy.FLIGHT_FEE;
                emit(new CloudRouteEvent.MoneyChanged(seat, -Economy.FLIGHT_FEE, MoneyReason.FLIGHT, cash[seat]));
            }
            flew = true;
            moveForward(seat, Board.distanceForward(pos[seat], tileId), MoveKind.FLY);
            land(seat, tileId);
        }

        /** 등불 선물(D-216) — 1단계 무료. */
        void giftBuild(int tileId) {
            int seat = turnSeat;
            if (owner[tileId] != seat || level[tileId] >= Economy.MAX_LEVEL - 1) {
                throw rejected(RejectionReason.INVALID_TILE);
            }
            level[tileId]++;
            emit(new CloudRouteEvent.Built(seat, tileId, level[tileId], 0, cash[seat]));
        }

        // ----- 편지 (§9) -----

        void drawLetter(int seat) {
            boolean reshuffled = false;
            if (deck.isEmpty()) {
                if (discard.isEmpty()) {
                    throw new IllegalStateException("no letters left to draw");
                }
                deck.addAll(discard);
                discard.clear();
                Collections.shuffle(deck, rng);
                reshuffled = true;
            }
            Letter letter = deck.removeFirst();
            emit(new CloudRouteEvent.LetterDrawn(seat, letter, deck.size(), reshuffled));
            if (letter != Letter.BUOY) {
                discard.add(letter);
            }
            switch (letter) {
                case TAILWIND -> moveForward(seat, Board.distanceForward(pos[seat], Board.START), MoveKind.LETTER);
                case SAIL_REPAIR -> payBank(seat, Economy.SAIL_REPAIR, MoneyReason.LETTER);
                case STAR_CHEST -> {
                    cash[seat] += Economy.STAR_CHEST;
                    emit(new CloudRouteEvent.MoneyChanged(seat, Economy.STAR_CHEST, MoneyReason.LETTER, cash[seat]));
                }
                case HEADWIND -> {
                    moveBack(seat, Economy.HEADWIND_STEPS);
                    land(seat, pos[seat]);
                }
                case WHIRLPOOL_ALERT -> sendToWhirlpool(seat);
                case BUOY -> buoyHolder = seat;
                case INSPECTION -> {
                    int levels = IntStream.range(0, Board.SIZE).filter(t -> owner[t] == seat).map(t -> level[t]).sum();
                    if (levels > 0) {
                        payBank(seat, levels * Economy.INSPECTION_PER_LEVEL, MoneyReason.LETTER);
                    }
                }
                case ISLAND_FEAST -> {
                    for (int i = 1; i < n; i++) {
                        int other = (seat + i) % n;
                        if (!alive(other)) {
                            continue;
                        }
                        Payment payment = charge(other, Economy.ISLAND_FEAST);
                        cash[seat] += payment.paid();
                        emit(new CloudRouteEvent.MoneyTransferred(other, seat, payment.paid(),
                                TransferReason.ISLAND_FEAST, cash[other], cash[seat]));
                        if (payment.bankrupt()) {
                            eliminate(other, Elimination.Reason.BANKRUPT, seat);
                            if (result != null) {
                                return;
                            }
                        }
                    }
                }
                case WIND_SHARE -> {
                    List<Integer> alive = aliveSeats();
                    int rich = alive.stream().max(Comparator.<Integer>comparingInt(s -> cash[s])
                            .thenComparing(Comparator.<Integer>reverseOrder())).orElseThrow();
                    int poor = alive.stream().min(Comparator.<Integer>comparingInt(s -> cash[s])
                            .thenComparing(Comparator.naturalOrder())).orElseThrow();
                    if (rich != poor) {
                        int amount = Math.min(Economy.WIND_SHARE, cash[rich]);
                        cash[rich] -= amount;
                        cash[poor] += amount;
                        emit(new CloudRouteEvent.MoneyTransferred(rich, poor, amount, TransferReason.WIND_SHARE,
                                cash[rich], cash[poor]));
                    }
                }
                case GUST -> {
                    moveForward(seat, Economy.GUST_STEPS, MoveKind.LETTER);
                    land(seat, pos[seat]);
                }
                case LANTERN_GIFT -> {
                    List<Integer> candidates = IntStream.range(0, Board.SIZE)
                            .filter(t -> owner[t] == seat && level[t] < Economy.MAX_LEVEL - 1).boxed().toList();
                    if (!candidates.isEmpty()) {
                        setPending(new Pending.AwaitGift(candidates));
                    }
                }
                case FREE_FLIGHT -> {
                    moveForward(seat, Board.distanceForward(pos[seat], Board.STATION), MoveKind.LETTER);
                    // 한 차례 비행 한 번(§6.6)은 무료권에도 같다 — 이미 날았으면 정류장에 도착만 한다.
                    if (!flew) {
                        setPending(new Pending.AwaitFlight(IntStream.range(0, Board.SIZE)
                                .filter(t -> t != Board.STATION).boxed().toList(), true));
                    }
                }
            }
        }

        // ----- 지불·청산·탈락 (§10 · §11) -----

        void payBank(int seat, int amount, MoneyReason reason) {
            Payment payment = charge(seat, amount);
            emit(new CloudRouteEvent.MoneyChanged(seat, -payment.paid(), reason, cash[seat]));
            if (payment.bankrupt()) {
                eliminate(seat, Elimination.Reason.BANKRUPT, -1);
            }
        }

        /** 모자라면 청산하고 낼 수 있는 만큼 낸다. 받는 쪽에 넣는 것은 호출자 몫. */
        Payment charge(int seat, int amount) {
            if (cash[seat] < amount) {
                liquidate(seat, amount);
            }
            int paid = Math.min(cash[seat], amount);
            cash[seat] -= paid;
            return new Payment(paid, paid < amount);
        }

        /** §10 — 건물(싼 땅부터 한 단계씩) → 땅(싼 것부터), 값의 절반(내림). */
        void liquidate(int seat, int need) {
            List<Sale> sales = new ArrayList<>();
            int clearedFestival = -1;
            while (cash[seat] < need) {
                int pick = cheapest(seat, true);
                if (pick >= 0) {
                    Tile tile = Board.tile(pick);
                    level[pick]--;
                    int received = tile.buildCost() / 2;
                    cash[seat] += received;
                    sales.add(new Sale(pick, false, level[pick], received));
                    continue;
                }
                pick = cheapest(seat, false);
                if (pick < 0) {
                    break;
                }
                Tile tile = Board.tile(pick);
                owner[pick] = -1;
                int received = tile.price() / 2;
                cash[seat] += received;
                sales.add(new Sale(pick, true, 0, received));
                if (festival == pick) {
                    festival = -1;
                    clearedFestival = pick;
                }
            }
            if (!sales.isEmpty()) {
                emit(new CloudRouteEvent.AssetsLiquidated(seat, sales, cash[seat]));
            }
            if (clearedFestival >= 0) {
                emit(new CloudRouteEvent.FestivalCleared(clearedFestival));
            }
        }

        /** 그 좌석의 땅 중 땅값이 가장 낮은 것(같으면 칸 번호가 낮은 것). {@code withBuilding} 이면 건물이 있는 땅만. */
        int cheapest(int seat, boolean withBuilding) {
            int pick = -1;
            for (int t = 0; t < Board.SIZE; t++) {
                if (owner[t] != seat || (withBuilding && level[t] == 0)) {
                    continue;
                }
                if (pick < 0 || Board.tile(t).price() < Board.tile(pick).price()) {
                    pick = t;
                }
            }
            return pick;
        }

        void eliminate(int seat, Elimination.Reason reason, int creditor) {
            List<Integer> returned = new ArrayList<>();
            int clearedFestival = -1;
            for (int t = 0; t < Board.SIZE; t++) {
                if (owner[t] == seat) {
                    owner[t] = -1;
                    level[t] = 0;
                    returned.add(t);
                    if (festival == t) {
                        festival = -1;
                        clearedFestival = t;
                    }
                }
            }
            if (clearedFestival >= 0) {
                emit(new CloudRouteEvent.FestivalCleared(clearedFestival));
            }
            if (buoyHolder == seat) {
                buoyHolder = -1;
                discard.add(Letter.BUOY);
            }
            int forfeited = cash[seat];
            cash[seat] = 0;
            trapped[seat] = false;
            failed[seat] = 0;
            eliminations.add(new Elimination(seat, reason));
            emit(new CloudRouteEvent.PlayerEliminated(seat, reason, creditor, returned, forfeited));
            if (seat == turnSeat) {
                pending = null;
                rollAgain = false;
            } else if (pending instanceof Pending.AwaitTakeover offer && owner[offer.tile()] != offer.owner()) {
                // 인수 결정 중 그 땅 주인이 탈락했다 — 땅이 은행으로 갔으므로 결정은 사라진다(D-211).
                pending = null;
            }
            List<Integer> alive = aliveSeats();
            if (alive.size() == 1) {
                finish(EndReason.LAST_STANDING);
            } else if (alive.stream().allMatch(s -> context.botSeats().contains(s))) {
                finish(EndReason.NO_HUMANS);
            }
        }

        // ----- 종료 (§12) -----

        void finish(EndReason reason) {
            pending = null;
            rollAgain = false;
            turnSeat = -1;
            result = new MatchResult(reason, round, standings());
            emit(new CloudRouteEvent.MatchEnded(result));
        }

        int totalAssets(int seat) {
            int sum = cash[seat];
            for (int t = 0; t < Board.SIZE; t++) {
                if (owner[t] == seat) {
                    sum += Economy.assetValue(Board.tile(t), level[t]);
                }
            }
            return sum;
        }

        List<Standing> standings() {
            List<Standing> out = new ArrayList<>();
            List<Integer> alive = new ArrayList<>(aliveSeats());
            alive.sort(Comparator.<Integer>comparingInt(this::totalAssets).reversed()
                    .thenComparing(Comparator.naturalOrder()));
            int previousAssets = Integer.MIN_VALUE;
            int previousRank = 0;
            for (int i = 0; i < alive.size(); i++) {
                int seat = alive.get(i);
                int assets = totalAssets(seat);
                int rank = assets == previousAssets ? previousRank : i + 1;
                out.add(new Standing(seat, rank, assets, cash[seat], SeatStatus.ALIVE));
                previousAssets = assets;
                previousRank = rank;
            }
            List<Elimination> bankrupt = eliminations.stream()
                    .filter(e -> e.reason() == Elimination.Reason.BANKRUPT).toList();
            int rank = alive.size() + 1;
            for (int i = bankrupt.size() - 1; i >= 0; i--) {
                out.add(new Standing(bankrupt.get(i).seat(), rank++, 0, 0, SeatStatus.BANKRUPT));
            }
            List<Integer> deserters = eliminations.stream()
                    .filter(e -> e.reason() == Elimination.Reason.DESERTED).map(Elimination::seat).sorted().toList();
            for (int seat : deserters) {
                out.add(new Standing(seat, rank, 0, 0, SeatStatus.DESERTED));
            }
            return out;
        }
    }
}
