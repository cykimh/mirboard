package com.mirboard.domain.game.cloudroute;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.board.Tile;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.AssetsLiquidated;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.LeaveMethod;
import com.mirboard.domain.game.cloudroute.invariant.CloudRouteInvariantChecker;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.MatchResult.EndReason;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.core.GameContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `docs/rules-cloudroute.md` §16 — 판을 끝까지 돌린다. 매 전이 뒤에 세 가지를 본다: 상태 불변식, 돈 장부(현금 합의 변화 = 이벤트가
 * 말하는 은행 흐름), 그리고 이벤트만 듣는 클라이언트가 같은 공개 상태를 그려 내는지(공개 상태가 바뀌는 곳마다 이벤트가 있다 — D-126 교훈).
 *
 * <p>두 가지 수로 돈다. <b>무작위</b>(합법수 균등 + 가끔 탈주·봇 좌석)는 규칙의 구석을 훑고, <b>상식</b>(잔고를 남기고 사고 짓는 단순
 * 정책 — S2 봇 정책이 아니다)은 실제 판 길이를 어림한다. 길이 통계는 {@code server/build/cloudroute-sim-stats.txt} 에 남긴다
 * (설계서 §4 의 수치 조정 근거).
 */
class CloudRouteMatchSimulationTest {

    private static final int GAMES_PER_SEAT_COUNT = 2_000;
    /** 전이 수 안전 한도 — 상한 라운드 × 좌석 × (결정·더블) 의 넉넉한 곱. */
    private static final int STEP_GUARD = 30 * 4 * 20;
    /** 설계서 §4 의 사람 차례 평균 가정(초). */
    private static final int SECONDS_PER_HUMAN_TURN = 15;
    private static final int RESERVE = 300;

    private static final Map<String, String> REPORT = new java.util.TreeMap<>();

    enum Policy { RANDOM, SENSIBLE }

    private record Outcome(EndReason reason, int rounds, int turns, int bankrupts, int firstBankruptRound) {
    }

    @ParameterizedTest(name = "{0}인 무작위 {1}판")
    @ValueSource(ints = {2, 3, 4})
    @Timeout(120)
    void random_play_always_ends_and_keeps_every_invariant(int seats) {
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        for (int game = 0; game < GAMES_PER_SEAT_COUNT; game++) {
            long seed = seats * 1_000_003L + game;
            Random pick = new Random(seed ^ 0x5DEECE66DL);
            Set<Integer> bots = new HashSet<>();
            if (pick.nextInt(3) == 0) {
                for (int seat = 0; seat < seats; seat++) {
                    if (pick.nextBoolean()) {
                        bots.add(seat);
                    }
                }
                bots.remove(pick.nextInt(seats)); // 사람이 적어도 한 명 앉는다(봇만의 판은 만들어지지 않는다)
            }
            Outcome outcome = play(seats, seed, bots, Policy.RANDOM, true);
            reasons.merge(outcome.reason(), 1, Integer::sum);
        }
        // 사유가 모두 실제로 나온다 — 한쪽 경로가 죽어 있지 않다. 2인은 한 명이 빠지면 늘 1명만 남아 NO_HUMANS 가 없다(§12.1 순서).
        assertThat(reasons).containsKeys(EndReason.ROUND_LIMIT, EndReason.LAST_STANDING);
        if (seats > 2) {
            assertThat(reasons).containsKey(EndReason.NO_HUMANS);
        } else {
            assertThat(reasons).doesNotContainKey(EndReason.NO_HUMANS);
        }
    }

    @ParameterizedTest(name = "{0}인 상식 {1}판 — 길이 통계")
    @ValueSource(ints = {2, 3, 4})
    @Timeout(120)
    void sensible_play_ends_within_the_round_limit_and_reports_length(int seats) {
        List<Outcome> outcomes = new ArrayList<>();
        for (int game = 0; game < GAMES_PER_SEAT_COUNT; game++) {
            outcomes.add(play(seats, 7_919L * seats + game, Set.of(), Policy.SENSIBLE, false));
        }
        int limit = Economy.roundLimit(seats);
        double avgTurns = outcomes.stream().mapToInt(Outcome::turns).average().orElseThrow();
        double avgRounds = outcomes.stream().mapToInt(Outcome::rounds).average().orElseThrow();
        double avgBankrupts = outcomes.stream().mapToInt(Outcome::bankrupts).average().orElseThrow();
        long limitHit = outcomes.stream().filter(o -> o.reason() == EndReason.ROUND_LIMIT).count();
        long lastStanding = outcomes.stream().filter(o -> o.reason() == EndReason.LAST_STANDING).count();
        List<Integer> firsts = outcomes.stream().map(Outcome::firstBankruptRound).filter(r -> r > 0).sorted().toList();
        long earlyFirst = firsts.stream().filter(r -> r <= limit / 2).count();
        String line = ("%d인(상한 %d): 평균 차례 %.1f · 평균 라운드 %.1f · 상한 도달 %.1f%% · 1명 남음 %.1f%% · 판당 파산 %.2f명 · "
                + "첫 파산 있는 판 %.1f%%, 그 라운드 중앙값 %s · 상한 절반 전 첫 파산 %.1f%% · 사람만 %ds/차례 가정 %.1f분")
                .formatted(seats, limit, avgTurns, avgRounds, 100.0 * limitHit / outcomes.size(),
                        100.0 * lastStanding / outcomes.size(), avgBankrupts, 100.0 * firsts.size() / outcomes.size(),
                        firsts.isEmpty() ? "-" : firsts.get(firsts.size() / 2), 100.0 * earlyFirst / outcomes.size(),
                        SECONDS_PER_HUMAN_TURN, avgTurns * SECONDS_PER_HUMAN_TURN / 60.0);
        REPORT.put("%d".formatted(seats), line);
        // 설계서 §1-3: 4인 평균 차례 ≤ 80 (상한이 그렇게 만든다 — 넘으면 라운드 셈이 틀렸다).
        assertThat(avgTurns).isLessThanOrEqualTo(seats * (double) limit);
        assertThat(outcomes).allMatch(o -> o.rounds() <= limit);
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (REPORT.isEmpty()) {
            return;
        }
        Path out = Path.of("build", "cloudroute-sim-stats.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "구름항로 시뮬레이션(상식 정책, 인원별 " + GAMES_PER_SEAT_COUNT + "판, 시드 고정)\n"
                + String.join("\n", REPORT.values()) + "\n");
    }

    private static Outcome play(int seats, long seed, Set<Integer> bots, Policy policy, boolean deserts) {
        Random rng = new Random(seed);
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        CloudRouteEngine engine = new CloudRouteEngine(
                new GameContext("sim", ids, 0, 0, new ArrayList<>(bots)), new Random(seed * 31 + 7));
        EventMirror mirror = new EventMirror(seats);
        int step = 0;
        String transition = "startMatch";
        List<CloudRouteEvent> events = List.of();
        int turns = 0;
        int firstBankruptRound = 0;
        try {
            CloudRouteEngine.Result start = engine.startMatch();
            CloudRouteState state = start.newState();
            events = start.events();
            verify(null, state, events, mirror, bots);
            for (; step < STEP_GUARD; step++) {
                turns += (int) events.stream().filter(CloudRouteEvent.TurnChanged.class::isInstance).count();
                if (firstBankruptRound == 0 && events.stream().anyMatch(e -> e instanceof CloudRouteEvent.PlayerEliminated p
                        && p.reason() == Elimination.Reason.BANKRUPT)) {
                    firstBankruptRound = state.round();
                }
                if (state.ended()) {
                    int bankrupts = (int) state.eliminations().stream()
                            .filter(e -> e.reason() == Elimination.Reason.BANKRUPT).count();
                    return new Outcome(state.result().reason(), state.round(), turns, bankrupts, firstBankruptRound);
                }
                CloudRouteState before = state;
                if (deserts && rng.nextInt(300) == 0) {
                    transition = "desert";
                    List<Integer> alive = state.aliveSeats();
                    CloudRouteEngine.Desertion d = engine.desert(state, alive.get(rng.nextInt(alive.size())));
                    if (d.outcome() == CloudRouteEngine.Desertion.Outcome.NOT_APPLICABLE
                            || (d.outcome() == CloudRouteEngine.Desertion.Outcome.MATCH_ENDED) != d.newState().ended()) {
                        throw new AssertionError("desertion outcome " + d.outcome() + " vs ended " + d.newState().ended());
                    }
                    state = d.newState();
                    events = d.events();
                } else {
                    int seat = state.turnSeat();
                    List<CloudRouteAction> legal = engine.legalActions(state, seat);
                    if (legal.isEmpty() || !legal.contains(engine.timeoutAction(state, seat))) {
                        throw new AssertionError("legal " + legal + " lacks timeout " + engine.timeoutAction(state, seat));
                    }
                    CloudRouteAction action = policy == Policy.RANDOM
                            ? legal.get(rng.nextInt(legal.size()))
                            : sensible(state, seat, legal);
                    transition = action.toString();
                    CloudRouteEngine.Result r = engine.apply(state, seat, action);
                    state = r.newState();
                    events = r.events();
                }
                verify(before, state, events, mirror, bots);
            }
            throw new AssertionError("match did not end within " + STEP_GUARD + " steps");
        } catch (RuntimeException | AssertionError e) {
            throw new AssertionError("%d인 seed=%d step=%d bots=%s 전이=%s 이벤트=%s — %s"
                    .formatted(seats, seed, step, bots, transition, events, e), e);
        }
    }

    /** 잔고 {@value #RESERVE}G 를 남기고 사고 짓는 단순한 수 — 실제 판 길이 어림용(S2 봇 정책 아님). */
    private static CloudRouteAction sensible(CloudRouteState s, int seat, List<CloudRouteAction> legal) {
        int cash = s.cash(seat);
        return switch (s.pending()) {
            case Pending.AwaitRoll __ -> new CloudRouteAction.Roll();
            case Pending.AwaitWhirlpool w -> {
                long unowned = Board.tiles().stream().filter(t -> Board.isLand(t.id()) && s.owners().get(t.id()) < 0).count();
                if (w.canUseBuoy()) {
                    yield new CloudRouteAction.UseBuoy();
                }
                yield w.canPay() && unowned >= 4 ? new CloudRouteAction.PayEscape() : new CloudRouteAction.Roll();
            }
            case Pending.AwaitPurchase p -> {
                Tile tile = Board.tile(p.tile());
                for (int level = p.maxLevel(); level >= 0; level--) {
                    if (cash - tile.price() - level * tile.buildCost() >= RESERVE) {
                        yield new CloudRouteAction.Buy(level);
                    }
                }
                yield new CloudRouteAction.Decline();
            }
            case Pending.AwaitBuild b -> {
                Tile tile = Board.tile(b.tile());
                int current = s.levels().get(b.tile());
                for (int level = b.maxLevel(); level >= b.minLevel(); level--) {
                    if (cash - (level - current) * tile.buildCost() >= RESERVE) {
                        yield new CloudRouteAction.Build(level);
                    }
                }
                yield new CloudRouteAction.Skip();
            }
            case Pending.AwaitTakeover t -> cash - t.price() >= RESERVE
                    ? new CloudRouteAction.Takeover() : new CloudRouteAction.Skip();
            case Pending.AwaitFestival f -> new CloudRouteAction.HostFestival(f.candidates().stream()
                    .max(java.util.Comparator.comparingInt(t -> Board.tile(t).tolls().get(s.levels().get(t))))
                    .orElseThrow());
            case Pending.AwaitFlight f -> f.candidates().stream()
                    .filter(t -> Board.isLand(t) && s.owners().get(t) < 0
                            && cash - Economy.FLIGHT_FEE - Board.tile(t).price() >= RESERVE)
                    .max(java.util.Comparator.comparingInt(t -> Board.tile(t).price()))
                    .<CloudRouteAction>map(CloudRouteAction.Fly::new)
                    .orElse(new CloudRouteAction.Skip());
        };
    }

    private static void verify(CloudRouteState before, CloudRouteState after, List<CloudRouteEvent> events,
                               EventMirror mirror, Set<Integer> bots) {
        CloudRouteInvariantChecker.check(after);
        if (before != null) {
            long delta = after.seats().stream().mapToLong(s -> s.cash()).sum()
                    - before.seats().stream().mapToLong(s -> s.cash()).sum();
            long flow = bankFlow(events);
            if (delta != flow) {
                throw new AssertionError("돈 장부: 현금 합 변화 " + delta + " ≠ 은행 흐름 " + flow);
            }
            if (after.version() != before.version() + 1) {
                throw new AssertionError("version " + before.version() + " → " + after.version());
            }
        }
        mirror.apply(events);
        mirror.assertMatches(after);
        boolean humanAlive = after.aliveSeats().stream().anyMatch(seat -> !bots.contains(seat));
        if (!after.ended() && !humanAlive) {
            throw new AssertionError("no human alive but the match is open");
        }
    }

    /** 이벤트가 말하는 은행과의 돈 — 사람 사이 이동(통행료·인수·잔치·나눔)은 합이 0 이라 빠진다. */
    private static long bankFlow(List<CloudRouteEvent> events) {
        long flow = 0;
        for (CloudRouteEvent e : events) {
            flow += switch (e) {
                case CloudRouteEvent.TokenMoved m -> m.salary();
                case CloudRouteEvent.LandBought b -> -b.paid();
                case CloudRouteEvent.Built b -> -b.paid();
                case CloudRouteEvent.MoneyChanged m -> m.delta();
                case CloudRouteEvent.AssetsLiquidated l -> l.sales().stream().mapToInt(AssetsLiquidated.Sale::received).sum();
                case CloudRouteEvent.PlayerEliminated p -> -p.cashForfeited();
                default -> 0;
            };
        }
        return flow;
    }

    /** 이벤트만 듣는 클라이언트 — 공개 이벤트로 판을 다시 그린다. */
    private static final class EventMirror {
        private final int n;
        private final int[] cash;
        private final int[] pos;
        private final boolean[] trapped;
        private final int[] failed;
        private final int[] owner = new int[Board.SIZE];
        private final int[] level = new int[Board.SIZE];
        private final Set<Integer> eliminated = new HashSet<>();
        private int festival = -1;
        private int buoy = -1;
        private int deckCount = Letter.values().length;
        private int turnSeat = -1;
        private int round;
        private Pending pending;
        private boolean ended;

        EventMirror(int seats) {
            n = seats;
            cash = new int[seats];
            pos = new int[seats];
            trapped = new boolean[seats];
            failed = new int[seats];
            Arrays.fill(cash, Economy.START_CASH);
            Arrays.fill(owner, -1);
        }

        void apply(List<CloudRouteEvent> events) {
            for (CloudRouteEvent event : events) {
                if (ended) {
                    throw new AssertionError("MatchEnded 뒤에 이벤트: " + event);
                }
                if (event.isPrivate() || !event.sequenced()) {
                    throw new AssertionError("구름항로 이벤트는 전부 공개·순번이다: " + event);
                }
                switch (event) {
                    case CloudRouteEvent.DiceRolled __ -> {
                    }
                    case CloudRouteEvent.TokenMoved m -> {
                        pos[m.seat()] = m.to();
                        cash[m.seat()] = m.cash();
                    }
                    case CloudRouteEvent.LandBought b -> {
                        owner[b.tile()] = b.seat();
                        level[b.tile()] = b.level();
                        cash[b.seat()] = b.cash();
                    }
                    case CloudRouteEvent.Built b -> {
                        level[b.tile()] = b.level();
                        cash[b.seat()] = b.cash();
                    }
                    case CloudRouteEvent.TollPaid t -> {
                        cash[t.payer()] = t.payerCash();
                        cash[t.owner()] = t.ownerCash();
                    }
                    case CloudRouteEvent.TakenOver t -> {
                        owner[t.tile()] = t.buyer();
                        cash[t.buyer()] = t.buyerCash();
                        cash[t.seller()] = t.sellerCash();
                    }
                    case CloudRouteEvent.LetterDrawn l -> {
                        deckCount = l.deckCount();
                        if (l.letter() == Letter.BUOY) {
                            buoy = l.seat();
                        }
                    }
                    case CloudRouteEvent.MoneyChanged m -> cash[m.seat()] = m.cash();
                    case CloudRouteEvent.MoneyTransferred m -> {
                        cash[m.from()] = m.fromCash();
                        cash[m.to()] = m.toCash();
                    }
                    case CloudRouteEvent.WhirlpoolEntered w -> {
                        trapped[w.seat()] = true;
                        failed[w.seat()] = 0;
                    }
                    case CloudRouteEvent.EscapeFailed f -> failed[f.seat()] = f.failedEscapes();
                    case CloudRouteEvent.WhirlpoolLeft w -> {
                        trapped[w.seat()] = false;
                        failed[w.seat()] = 0;
                        if (w.method() == LeaveMethod.BUOY) {
                            buoy = -1;
                        }
                    }
                    case CloudRouteEvent.FestivalHosted f -> festival = f.tile();
                    case CloudRouteEvent.FestivalCleared __ -> festival = -1;
                    case CloudRouteEvent.AssetsLiquidated l -> {
                        for (AssetsLiquidated.Sale sale : l.sales()) {
                            if (sale.land()) {
                                owner[sale.tile()] = -1;
                                level[sale.tile()] = 0;
                            } else {
                                level[sale.tile()] = sale.levelAfter();
                            }
                        }
                        cash[l.seat()] = l.cash();
                    }
                    case CloudRouteEvent.PlayerEliminated p -> {
                        for (int tile : p.returnedTiles()) {
                            owner[tile] = -1;
                            level[tile] = 0;
                        }
                        eliminated.add(p.seat());
                        cash[p.seat()] = 0;
                        trapped[p.seat()] = false;
                        failed[p.seat()] = 0;
                        if (buoy == p.seat()) {
                            buoy = -1;
                        }
                        if (p.seat() == turnSeat) {
                            pending = null;
                        }
                    }
                    case CloudRouteEvent.TurnChanged t -> {
                        turnSeat = t.seat();
                        round = t.round();
                    }
                    case CloudRouteEvent.DecisionRequired d -> {
                        if (d.seat() != turnSeat) {
                            throw new AssertionError("결정 좌석 " + d.seat() + " ≠ 차례 " + turnSeat);
                        }
                        pending = d.pending();
                    }
                    case CloudRouteEvent.MatchEnded m -> {
                        ended = true;
                        pending = null;
                        turnSeat = -1;
                        round = m.result().rounds();
                    }
                }
            }
        }

        void assertMatches(CloudRouteState s) {
            for (int seat = 0; seat < n; seat++) {
                same("seat " + seat + " cash", cash[seat], s.cash(seat));
                same("seat " + seat + " position", pos[seat], s.seat(seat).position());
                same("seat " + seat + " trapped", trapped[seat], s.seat(seat).trapped());
                same("seat " + seat + " failed", failed[seat], s.seat(seat).failedEscapes());
                same("seat " + seat + " eliminated", eliminated.contains(seat), !s.alive(seat));
            }
            for (int t = 0; t < Board.SIZE; t++) {
                same("tile " + t + " owner", owner[t], s.owners().get(t));
                same("tile " + t + " level", level[t], s.levels().get(t));
            }
            same("festival", festival, s.festivalTile());
            same("buoy", buoy, s.buoyHolder());
            same("deck count", deckCount, s.letterDeck().size());
            same("ended", ended, s.ended());
            same("turn", turnSeat, s.turnSeat());
            same("round", round, s.round());
            same("pending", pending, s.pending());
        }

        private static void same(String what, Object projected, Object actual) {
            if (!Objects.equals(projected, actual)) {
                throw new AssertionError(what + ": 이벤트로 그린 값 " + projected + " ≠ 상태 " + actual);
            }
        }
    }
}
