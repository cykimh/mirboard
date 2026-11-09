package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 무작위 행동 시뮬레이션 (`docs/rules-artillery.md` §12): 2~6인 각 200판(합 1,000판)이 전부 차례 상한 안에서 끝나고, 매 전이 뒤
 * 불변식이 지켜진다. 무작위 발사·이동·넘김에 가끔 탈주를 섞는다.
 */
class ArtillerySimulationTest {

    private static final ArtilleryEngine ENGINE = new ArtilleryEngine();
    private static final int GAMES = 200;

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void random_matches_all_end_within_the_turn_limit_and_keep_invariants(int seats) {
        Random random = new Random(20261109L + seats);
        Map<MatchResult.EndReason, Integer> reasons = new EnumMap<>(MatchResult.EndReason.class);
        long fireNanos = 0;
        int fires = 0;
        int totalTurns = 0;
        for (int game = 0; game < GAMES; game++) {
            long now = 1_000_000L;
            ArtilleryState s = ENGINE.startMatch(random.nextLong(), seats, List.of()).newState();
            assertInvariants(s, game);
            int transitions = 0;
            while (!s.ended()) {
                transitions++;
                assertThat(transitions).as("game %d transitions", game).isLessThan(10_000);
                if (s.phase() == Phase.RESOLVING) {
                    now = s.resolveUntil();
                    s = ENGINE.expire(s, now).newState();
                } else if (random.nextInt(100) < 2) {
                    int seat = random.nextInt(seats);
                    s = ENGINE.desert(s, seat).newState();
                } else {
                    int seat = s.turnSeat();
                    ArtilleryAction action;
                    int roll = random.nextInt(100);
                    if (roll < 8) {
                        action = new ArtilleryAction.Skip();
                    } else if (roll < 10) {
                        s = ENGINE.timeout(s, now).newState();
                        assertInvariants(s, game);
                        continue;
                    } else {
                        Shell shell = s.unit(seat).pumpkins() > 0 && random.nextInt(5) == 0 ? Shell.PUMPKIN : Shell.PEA;
                        action = new ArtilleryAction.Fire(random.nextInt(61) - 30, random.nextInt(181),
                                20 + random.nextInt(81), shell);
                    }
                    long t0 = System.nanoTime();
                    ArtilleryEngine.Result r = ENGINE.apply(s, seat, action, now);
                    if (action instanceof ArtilleryAction.Fire) {
                        fireNanos += System.nanoTime() - t0;
                        fires++;
                        assertThat(r.events().getFirst()).isInstanceOf(ArtilleryEvent.ShotResolved.class);
                    }
                    s = r.newState();
                }
                assertInvariants(s, game);
            }
            totalTurns += s.turnNo() + 1;
            assertThat(s.turnNo()).isLessThan(ArtilleryEngine.turnLimit(seats));
            reasons.merge(s.result().reason(), 1, Integer::sum);
        }
        System.out.printf("[artillery-sim] seats=%d games=%d avgTurns=%.1f reasons=%s avgFire=%.3fms%n",
                seats, GAMES, totalTurns / (double) GAMES, reasons, fireNanos / 1e6 / Math.max(1, fires));
    }

    private static void assertInvariants(ArtilleryState s, int game) {
        assertThat(ArtilleryInvariantChecker.check(s)).as("game %d turn %d", game, s.turnNo()).isEmpty();
    }
}
