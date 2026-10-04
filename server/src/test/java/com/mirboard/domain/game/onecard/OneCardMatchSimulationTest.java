package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `docs/rules-onecard.md` §14 — 무작위 합법수로 판을 끝까지 돌리며 매 전이 뒤 불변식을 검사한다. 경쟁 창은
 * 무작위로 누르거나 시간을 흘려 닫고, 가끔 탈주를 끼워 넣는다.
 */
class OneCardMatchSimulationTest {

    private static final int GAMES_PER_SEAT_COUNT = 2_000;
    private static final int BOT_GAMES_PER_SEAT_COUNT = 300;
    /** 차례 상한(600) + 경쟁 창·탈주로 늘어나는 전이. 넘으면 끝나지 않는 판이다. */
    private static final int STEP_GUARD = 3 * OneCardEngine.TURN_LIMIT;

    private record Outcome(EndReason reason, boolean hitTurnLimit) {
    }

    private static Outcome play(int seats, long seed, Set<Integer> bots) {
        Random rng = new Random(seed);
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        OneCardEngine engine = new OneCardEngine(
                new GameContext("sim", ids, 0, 0, new ArrayList<>(bots)), new Random(seed * 31 + 7));
        OneCardState state = engine.startMatch().newState();
        OneCardInvariantChecker.check(state);
        long now = 0;
        for (int step = 0; step < STEP_GUARD; step++) {
            if (state.ended()) {
                return new Outcome(state.result().reason(), state.turnCount() >= OneCardEngine.TURN_LIMIT);
            }
            now += 400;
            if (rng.nextInt(400) == 0) {
                List<Integer> alive = state.aliveSeats();
                state = engine.desert(state, alive.get(rng.nextInt(alive.size()))).newState();
            } else if (state.race() != null) {
                state = stepRace(engine, state, rng, bots, now);
            } else {
                int seat = state.turnSeat();
                state = engine.apply(state, seat, choose(engine.legalActions(state, seat), rng), now).newState();
            }
            OneCardInvariantChecker.check(state);
        }
        throw new AssertionError("match did not end within " + STEP_GUARD + " steps (seed " + seed + ")");
    }

    /** 경쟁 창 — 3분의 1은 시간이 흘러 닫히고(봇 누름 또는 만료), 나머지는 살아 있는 사람이 누른다. */
    private static OneCardState stepRace(OneCardEngine engine, OneCardState state, Random rng, Set<Integer> bots,
                                         long now) {
        List<Integer> humans = state.aliveSeats().stream().filter(seat -> !bots.contains(seat)).toList();
        if (humans.isEmpty() || rng.nextInt(3) == 0) {
            return engine.onTimer(state).orElseThrow().newState();
        }
        int presser = humans.get(rng.nextInt(humans.size()));
        return engine.apply(state, presser, engine.legalActions(state, presser).getFirst(), now).newState();
    }

    /** 낼 수 있으면 열에 아홉은 무작위 카드를 낸다 — 균등 추첨이면 먹기가 너무 잦아 판이 늘어진다. */
    private static OneCardAction choose(List<OneCardAction> legal, Random rng) {
        List<OneCardAction> plays = legal.stream().filter(OneCardAction.PlayCard.class::isInstance).toList();
        if (!plays.isEmpty() && rng.nextInt(10) < 9) {
            return plays.get(rng.nextInt(plays.size()));
        }
        return new OneCardAction.Draw();
    }

    @ParameterizedTest(name = "{0}인 × " + GAMES_PER_SEAT_COUNT + "판")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void every_match_ends_with_the_cards_conserved(int seats) {
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        int turnLimitHits = 0;
        for (int game = 0; game < GAMES_PER_SEAT_COUNT; game++) {
            Outcome outcome = play(seats, seats * 1_000_003L + game, Set.of());
            reasons.merge(outcome.reason(), 1, Integer::sum);
            if (outcome.hitTurnLimit()) {
                turnLimitHits++;
            }
        }

        assertThat(reasons.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(GAMES_PER_SEAT_COUNT);
        assertThat(reasons).containsKey(EndReason.FINISHED);
        // 차례 상한은 안전장치다 — 전략 없는 무작위 판에서도 열에 아홉은 그 전에 끝나야 한다(§11.3, §14).
        // 측정값(시드 고정): 2인 1% 미만 · 3인 1.0% · 4인 2.1% · 5인 2.3% · 6인 5.1%.
        assertThat(turnLimitHits).as("차례 상한 도달 %d판 / %s", turnLimitHits, reasons)
                .isLessThan(GAMES_PER_SEAT_COUNT / 10);
    }

    /** 봇 좌석은 경쟁 창에서 엔진 타이머({@code onTimer})의 봇 누름으로만 참여한다. */
    @ParameterizedTest(name = "{0}인(사람 1 + 봇) × " + BOT_GAMES_PER_SEAT_COUNT + "판")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void matches_with_one_human_and_bots_always_end(int seats) {
        Set<Integer> bots = IntStream.range(1, seats).boxed().collect(Collectors.toSet());
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        for (int game = 0; game < BOT_GAMES_PER_SEAT_COUNT; game++) {
            reasons.merge(play(seats, seats * 7_919L + game, bots).reason(), 1, Integer::sum);
        }

        assertThat(reasons.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(BOT_GAMES_PER_SEAT_COUNT);
        if (seats >= 3) {
            // 사람이 탈주·파산해 봇만 남으면 그 자리에서 끝난다(§11.1 NO_HUMANS). 2인은 1명만 남아 LAST_STANDING.
            assertThat(reasons).as("%s", reasons).containsKey(EndReason.NO_HUMANS);
        }
    }
}
