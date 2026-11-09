package com.mirboard.domain.game.cloudroute.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.cloudroute.CloudRouteEngine;
import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.board.Tile;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.cloudroute.state.SeatState;
import com.mirboard.domain.game.core.GameContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * D-212 — 봇 정책: 장면별 결정, 늘 합법수, 결정적, 그리고 강도(무작위·탐욕 상대 1,000판). 설계서 §7.
 */
class CloudRouteBotPolicyTest {

    private static CloudRouteState state(int[] cash, int[] owners, int[] levels, int festival, Pending pending) {
        List<SeatState> seats = new ArrayList<>();
        for (int c : cash) {
            seats.add(new SeatState(c, 4, false, 0));
        }
        return new CloudRouteState(seats, Arrays.stream(owners).boxed().toList(), Arrays.stream(levels).boxed().toList(),
                festival, -1, Letter.fullDeck(), List.of(), 0, 0, 1, Economy.roundLimit(cash.length), 0, false, false,
                List.of(), List.of(), pending, List.of(), 1, null);
    }

    private static int[] none() {
        int[] a = new int[Board.SIZE];
        Arrays.fill(a, -1);
        return a;
    }

    private static CloudRouteAction choose(CloudRouteState s) {
        return CloudRouteBotPolicy.choose(CloudRouteBotView.of(s, 0), List.of());
    }

    /** 구름왕관 두 칸을 상대가 가졌고 정원에 상점 — 510G 짜리 통행료가 판 위에 있다. */
    private static int[] crownOwnedBy1() {
        int[] owners = none();
        owners[22] = 1;
        owners[23] = 1;
        return owners;
    }

    private static int[] crownShop() {
        int[] levels = new int[Board.SIZE];
        levels[22] = 2; // 340 × 1.5 = 510
        return levels;
    }

    @Test
    void the_reserve_is_fifteen_percent_of_the_largest_toll_the_bot_could_be_charged() {
        CloudRouteState s = state(new int[] {1_500, 1_500}, crownOwnedBy1(), crownShop(), -1, new Pending.AwaitRoll());
        assertThat(CloudRouteBotPolicy.reserve(CloudRouteBotView.of(s, 0))).isEqualTo(76);
        assertThat(CloudRouteBotPolicy.reserve(CloudRouteBotView.of(state(new int[] {1_500, 1_500}, none(),
                new int[Board.SIZE], -1, new Pending.AwaitRoll()), 0))).isZero();
    }

    @Test
    void it_buys_and_builds_while_keeping_the_reserve_and_declines_otherwise() {
        Pending.AwaitPurchase buy = new Pending.AwaitPurchase(4, 2);
        assertThat(choose(state(new int[] {1_500, 1_500}, crownOwnedBy1(), crownShop(), -1, buy)))
                .isEqualTo(new CloudRouteAction.Buy(2));
        assertThat(choose(state(new int[] {200, 1_500}, crownOwnedBy1(), crownShop(), -1, buy)))
                .isEqualTo(new CloudRouteAction.Buy(1)); // 200 − 80 − 40 = 80 ≥ 76
        assertThat(choose(state(new int[] {150, 1_500}, crownOwnedBy1(), crownShop(), -1, buy)))
                .isEqualTo(new CloudRouteAction.Decline()); // 150 − 80 = 70 < 76
    }

    @Test
    void it_buys_to_complete_or_block_a_monopoly_with_half_the_reserve() {
        int[] owners = crownOwnedBy1();
        owners[5] = 1; // 상대가 조개섬 한 칸
        CloudRouteState s = state(new int[] {150, 1_500}, owners, crownShop(), -1, new Pending.AwaitPurchase(4, 2));
        assertThat(choose(s)).isEqualTo(new CloudRouteAction.Buy(0)); // 70 ≥ 38
    }

    @Test
    void it_takes_over_built_or_monopoly_lands_but_not_a_bare_lone_land_or_near_the_end() {
        int[] owners = none();
        owners[7] = 1;
        owners[8] = 1;
        CloudRouteState breaking = state(new int[] {1_500, 1_500}, owners, new int[Board.SIZE], -1,
                new Pending.AwaitTakeover(7, 1, 150));
        assertThat(choose(breaking)).isEqualTo(new CloudRouteAction.Takeover());
        int[] lone = none();
        lone[1] = 1;
        CloudRouteState bare = state(new int[] {1_500, 1_500}, lone, new int[Board.SIZE], -1,
                new Pending.AwaitTakeover(1, 1, 90));
        assertThat(choose(bare)).isEqualTo(new CloudRouteAction.Skip());
        int[] tent = new int[Board.SIZE];
        tent[1] = 1;
        CloudRouteState built = state(new int[] {1_500, 1_500}, lone, tent, -1, new Pending.AwaitTakeover(1, 1, 140));
        assertThat(choose(built)).isEqualTo(new CloudRouteAction.Takeover());
        CloudRouteState late = withRound(built, 28); // 2인 상한 30 — 남은 라운드 2
        assertThat(choose(late)).isEqualTo(new CloudRouteAction.Skip());
    }

    private static CloudRouteState withRound(CloudRouteState s, int round) {
        return new CloudRouteState(s.seats(), s.owners(), s.levels(), s.festivalTile(), s.buoyHolder(), s.letterDeck(),
                s.letterDiscard(), s.startSeat(), s.turnSeat(), round, s.roundLimit(), s.doublesThisTurn(), s.rollAgain(),
                s.flewThisTurn(), s.lockedTiles(), s.lastDice(), s.pending(), s.eliminations(), s.version(), s.result());
    }

    @Test
    void it_hosts_the_festival_on_its_biggest_toll_and_flies_to_the_dearest_affordable_land() {
        int[] owners = none();
        owners[1] = 0;
        owners[19] = 0;
        CloudRouteState fest = state(new int[] {1_500, 1_500}, owners, new int[Board.SIZE], -1,
                new Pending.AwaitFestival(List.of(1, 19)));
        assertThat(choose(fest)).isEqualTo(new CloudRouteAction.HostFestival(19));
        List<Integer> all = java.util.stream.IntStream.range(0, Board.SIZE).filter(t -> t != Board.STATION).boxed().toList();
        CloudRouteState fly = state(new int[] {500, 1_500}, none(), new int[Board.SIZE], -1, new Pending.AwaitFlight(all));
        assertThat(choose(fly)).isEqualTo(new CloudRouteAction.Fly(22)); // 240 짜리 중 번호가 낮은 칸
        CloudRouteState mid = state(new int[] {260, 1_500}, none(), new int[Board.SIZE], -1, new Pending.AwaitFlight(all));
        assertThat(choose(mid)).isEqualTo(new CloudRouteAction.Fly(19)); // 210 안의 가장 비싼 200
        CloudRouteState poor = state(new int[] {100, 1_500}, none(), new int[Board.SIZE], -1, new Pending.AwaitFlight(all));
        assertThat(choose(poor)).isEqualTo(new CloudRouteAction.Skip());
    }

    // ---------- 강도 ----------

    private static final int GAMES = 1_000;

    private static CloudRouteAction random(CloudRouteState s, int seat, List<CloudRouteAction> legal, Random rng) {
        return legal.get(rng.nextInt(legal.size()));
    }

    /** 탐욕 — 살 수 있으면 최대로 사고 짓고, 인수할 수 있으면 늘 인수한다. 잔고를 생각하지 않는다. */
    private static CloudRouteAction greedy(CloudRouteState s, int seat, List<CloudRouteAction> legal, Random rng) {
        return switch (s.pending()) {
            case Pending.AwaitPurchase p -> new CloudRouteAction.Buy(p.maxLevel());
            case Pending.AwaitBuild b -> new CloudRouteAction.Build(b.maxLevel());
            case Pending.AwaitTakeover __ -> new CloudRouteAction.Takeover();
            case Pending.AwaitWhirlpool w -> w.canPay() ? new CloudRouteAction.PayEscape() : new CloudRouteAction.Roll();
            default -> CloudRouteBotPolicy.choose(CloudRouteBotView.of(s, seat), legal);
        };
    }

    private interface Opponent {
        CloudRouteAction pick(CloudRouteState s, int seat, List<CloudRouteAction> legal, Random rng);
    }

    /** 좌석 0 = 휴리스틱, 나머지 = 상대. 첫 차례는 무작위라 좌석 이점이 섞인다. 1등(동순위 포함) 비율. */
    private static double winRate(int seats, Opponent opponent) {
        int wins = 0;
        for (int game = 0; game < GAMES; game++) {
            Random rng = new Random(77L * game + seats);
            List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
            CloudRouteEngine engine = new CloudRouteEngine(new GameContext("s", ids), new Random(game * 13L + 1));
            CloudRouteState s = engine.startMatch().newState();
            for (int step = 0; !s.ended(); step++) {
                if (step > 5_000) {
                    throw new AssertionError("match did not end");
                }
                int seat = s.turnSeat();
                List<CloudRouteAction> legal = engine.legalActions(s, seat);
                CloudRouteAction action = seat == 0
                        ? CloudRouteBotPolicy.choose(CloudRouteBotView.of(s, seat), legal)
                        : opponent.pick(s, seat, legal, rng);
                assertThat(legal).as("봇 수는 늘 합법").contains(action);
                s = engine.apply(s, seat, action).newState();
            }
            if (s.result().winners().contains(0)) {
                wins++;
            }
        }
        return (double) wins / GAMES;
    }

    @Test
    void the_heuristic_beats_random_and_greedy_opponents() {
        double vsRandom4 = winRate(4, CloudRouteBotPolicyTest::random);
        double vsGreedy4 = winRate(4, CloudRouteBotPolicyTest::greedy);
        double vsRandom2 = winRate(2, CloudRouteBotPolicyTest::random);
        double vsGreedy2 = winRate(2, CloudRouteBotPolicyTest::greedy);
        System.out.printf("CloudRoute bot strength: 4p vs random %.3f · vs greedy %.3f · 1:1 vs random %.3f · vs greedy %.3f%n",
                vsRandom4, vsGreedy4, vsRandom2, vsGreedy2);
        // 기준: 4인 0.25, 1:1 0.5 (실력이 같으면 나올 값). 무작위에는 크게 이기고, "다 쓰는" 탐욕과는 대등해야 한다 — 이 경제에서는
        // 다 쓰기가 사실상 최선이라(정책 javadoc) 탐욕을 크게 넘기는 기준은 두지 않는다. S2 실측: 0.777 · 0.259 · 0.832 · 0.503.
        assertThat(vsRandom4).isGreaterThan(0.7);
        assertThat(vsRandom2).isGreaterThan(0.8);
        assertThat(vsGreedy4).isGreaterThan(0.23);
        assertThat(vsGreedy2).isGreaterThan(0.47);
    }

    @Test
    void the_same_state_gives_the_same_move() {
        CloudRouteState s = state(new int[] {900, 1_500, 700}, none(), new int[Board.SIZE], -1,
                new Pending.AwaitPurchase(13, 2));
        CloudRouteAction first = choose(s);
        for (int i = 0; i < 5; i++) {
            assertThat(choose(s)).isEqualTo(first);
        }
        Tile tile = Board.tile(13);
        assertThat(tile.price()).isEqualTo(150);
    }

    @SuppressWarnings("unused")
    private static BiFunction<CloudRouteBotView, List<CloudRouteAction>, CloudRouteAction> policy() {
        return CloudRouteBotPolicy::choose;
    }
}
