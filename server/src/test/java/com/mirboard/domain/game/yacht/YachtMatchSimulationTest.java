package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.invariant.YachtInvariantChecker;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.YachtState;
import com.mirboard.domain.game.yacht.state.YachtStateMapper;
import com.mirboard.domain.game.yacht.state.YachtStateMapper.PublicView;
import com.mirboard.domain.game.yacht.state.YachtStateMapper.SeatView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `docs/rules-yacht.md` §11 — 무작위 봇(합법수 균등) 판이 전부 끝나고, 매 전이 뒤 불변식을 지키며, 공개 이벤트만 듣는 클라가
 * 그린 테이블이 엔진의 공개 뷰와 같다. 판의 일부에는 무작위 탈주와 시간 초과(엔진 {@code expire})를 섞는다.
 */
class YachtMatchSimulationTest {

    private static final int MATCHES = 2_000;

    record Stats(int matches, int actionLimitEnds, int noHumansEnds, long actions) {
    }

    @ParameterizedTest(name = "{0}인 무작위 봇 2,000판")
    @ValueSource(ints = {1, 2, 3, 4})
    @Timeout(120)
    void random_matches_always_end_and_keep_every_invariant(int seats) {
        Stats stats = run(seats, List.of(), MATCHES, 0xA11CEL + seats, 0.0, 0.0);
        assertThat(stats.matches()).isEqualTo(MATCHES);
        assertThat(stats.noHumansEnds()).isZero();
        // 무작위 봇은 홀드를 많이 바꾸지만 차례당 상한 40 에는 닿지 않아야 한다(§11)
        assertThat(stats.actionLimitEnds()).isLessThan(MATCHES / 100);
        System.out.printf("yacht sim %d seats: %d matches, ACTION_LIMIT %d, actions/match %.1f%n",
                seats, stats.matches(), stats.actionLimitEnds(), (double) stats.actions() / stats.matches());
    }

    @Test
    @Timeout(120)
    void one_human_with_three_bots_survives_desertions_and_timeouts() {
        Stats stats = run(4, List.of(1, 2, 3), 300, 0xB0B5L, 0.002, 0.05);
        assertThat(stats.matches()).isEqualTo(300);
        assertThat(stats.noHumansEnds()).isPositive();
    }

    @Test
    @Timeout(120)
    void two_humans_with_desertions_and_timeouts() {
        Stats stats = run(3, List.of(2), 500, 0xC0FFEEL, 0.003, 0.05);
        assertThat(stats.matches()).isEqualTo(500);
    }

    @Test
    void the_same_dice_and_choices_replay_the_same_match() {
        assertThat(eventsOf(42L)).isEqualTo(eventsOf(42L));
        assertThat(eventsOf(42L)).isNotEqualTo(eventsOf(43L));
    }

    private List<YachtEvent> eventsOf(long seed) {
        YachtEngine engine = new YachtEngine(context(2, List.of()), 30);
        Random choices = new Random(seed);
        DiceRoller roller = DiceRoller.from(new Random(seed * 31));
        YachtEngine.Result start = engine.start(T0);
        List<YachtEvent> events = new ArrayList<>(start.events());
        YachtState s = start.newState();
        while (!s.ended()) {
            List<YachtAction> legal = engine.legalActions(s, s.turnSeat());
            YachtEngine.Result r = engine.apply(s, s.turnSeat(), legal.get(choices.nextInt(legal.size())), roller, T0);
            events.addAll(r.events());
            s = r.newState();
        }
        return events;
    }

    private static GameContext context(int seats, List<Integer> bots) {
        return new GameContext("sim", LongStream.rangeClosed(1, seats).boxed().toList(), 0, 0, bots);
    }

    private Stats run(int seats, List<Integer> bots, int matches, long seed, double desertRate, double expireRate) {
        Random random = new Random(seed);
        DiceRoller roller = DiceRoller.from(random);
        YachtEngine engine = new YachtEngine(context(seats, bots), 45);
        int actionLimit = 0;
        int noHumans = 0;
        long actions = 0;
        for (int m = 0; m < matches; m++) {
            long now = T0;
            YachtEngine.Result start = engine.start(now);
            YachtState s = start.newState();
            Client client = new Client(seats);
            client.applyAll(start.events());
            check(engine, s, client);

            while (!s.ended()) {
                now += 1_000;
                List<YachtEvent> events;
                if (random.nextDouble() < desertRate) {
                    List<Integer> alive = s.aliveSeats();
                    int seat = alive.get(random.nextInt(alive.size()));
                    YachtEngine.Desertion d = engine.desert(s, seat, now);
                    s = d.newState();
                    events = d.events();
                } else if (random.nextDouble() < expireRate) {
                    YachtEngine.Result r = engine.expire(s, roller, now);
                    s = r.newState();
                    events = r.events();
                } else {
                    List<YachtAction> legal = engine.legalActions(s, s.turnSeat());
                    YachtAction action = legal.get(random.nextInt(legal.size()));
                    // 클라가 홀드를 굴림에 실어 보내는 경로(§4-2)도 섞는다 — 다섯 개 모두는 아닌 무작위 마스크
                    if (action instanceof YachtAction.Roll && s.turn().rolls() > 0 && random.nextBoolean()) {
                        action = new YachtAction.Roll(new HoldMask(random.nextInt(HoldMask.ALL.bits())));
                    }
                    YachtEngine.Result r = engine.apply(s, s.turnSeat(), action, roller, now);
                    s = r.newState();
                    events = r.events();
                    actions++;
                }
                client.applyAll(events);
                check(engine, s, client);
            }
            if (s.result().reason() == EndReason.ACTION_LIMIT) {
                actionLimit++;
            } else if (s.result().reason() == EndReason.NO_HUMANS) {
                noHumans++;
            }
        }
        return new Stats(matches, actionLimit, noHumans, actions);
    }

    private static void check(YachtEngine engine, YachtState s, Client client) {
        YachtInvariantChecker.check(s, engine.botSeats());
        PublicView view = YachtStateMapper.toPublicView(s, null);
        assertThat(client.view()).as("event-only client vs engine view").isEqualTo(view);
    }

    /** 공개 이벤트만 듣는 클라 — 시작 상태(빈 점수표)에서 결과값 payload 만으로 테이블을 그린다. */
    private static final class Client {
        private final List<Map<Category, Integer>> scores = new ArrayList<>();
        private final int[] upper;
        private final int[] bonus;
        private final int[] total;
        private final boolean[] deserted;
        private int round;
        private int turnSeat = -1;
        private Dice dice;
        private HoldMask held = HoldMask.NONE;
        private int rolls;
        private Map<Category, Integer> candidates = Map.of();
        private MatchResult result;

        Client(int seats) {
            for (int i = 0; i < seats; i++) {
                scores.add(new EnumMap<>(Category.class));
            }
            upper = new int[seats];
            bonus = new int[seats];
            total = new int[seats];
            deserted = new boolean[seats];
        }

        void applyAll(List<YachtEvent> events) {
            events.forEach(this::apply);
        }

        void apply(YachtEvent e) {
            switch (e) {
                case YachtEvent.TurnChanged t -> {
                    turnSeat = t.seat();
                    round = t.round();
                    dice = null;
                    held = HoldMask.NONE;
                    rolls = 0;
                    candidates = Map.of();
                }
                case YachtEvent.DiceHeld h -> held = h.held();
                case YachtEvent.DiceRolled r -> {
                    dice = r.dice();
                    held = r.held();
                    rolls = r.rolls();
                    candidates = r.candidates();
                }
                case YachtEvent.Scored sc -> {
                    scores.get(sc.seat()).put(sc.category(), sc.points());
                    upper[sc.seat()] = sc.upperSubtotal();
                    bonus[sc.seat()] = sc.bonus();
                    total[sc.seat()] = sc.total();
                }
                case YachtEvent.PlayerEliminated p -> {
                    deserted[p.seat()] = true;
                    if (p.seat() == turnSeat) {
                        dice = null;
                        held = HoldMask.NONE;
                        rolls = 0;
                        candidates = Map.of();
                    }
                }
                case YachtEvent.MatchEnded m -> {
                    result = new MatchResult(m.reason(), m.placements());
                    turnSeat = -1;
                    dice = null;
                    held = HoldMask.NONE;
                    rolls = 0;
                    candidates = Map.of();
                }
            }
        }

        PublicView view() {
            List<SeatView> seats = IntStream.range(0, scores.size())
                    .mapToObj(s -> new SeatView(s, Map.copyOf(scores.get(s)), upper[s], bonus[s], total[s], deserted[s]))
                    .toList();
            return new PublicView(result != null ? "ENDED" : "PLAYING", seats, round, turnSeat, dice, held, rolls,
                    candidates, null, result);
        }
    }
}
