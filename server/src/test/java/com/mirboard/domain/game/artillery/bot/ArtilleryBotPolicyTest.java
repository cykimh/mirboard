package com.mirboard.domain.game.artillery.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 봇 "보통" (D-222): 결정적, 합법, 무작위보다 확실히 세고, 한 수 비용이 예산 안. Docker 불필요. */
class ArtilleryBotPolicyTest {

    private static final ArtilleryEngine ENGINE = new ArtilleryEngine();

    @Test
    void same_state_same_move_and_always_valid() {
        for (long seed = 1; seed <= 20; seed++) {
            ArtilleryState s = ENGINE.startMatch(seed, 3, List.of()).newState();
            ArtilleryAction a = ArtilleryBotPolicy.choose(s, s.turnSeat());
            assertThat(ArtilleryBotPolicy.choose(s, s.turnSeat())).isEqualTo(a);
            assertThat(a).isInstanceOf(ArtilleryAction.Fire.class);
            assertThat(ArtilleryEngine.validFire(s, s.turnSeat(), (ArtilleryAction.Fire) a)).isTrue();
        }
    }

    @Test
    void the_bot_beats_a_random_shooter_almost_always() {
        Random random = new Random(99);
        int games = 60;
        int botWins = 0;
        int firstShotHits = 0;
        for (int g = 0; g < games; g++) {
            ArtilleryState s = ENGINE.startMatch(random.nextLong(), 2, List.of()).newState();
            int botSeat = g % 2;
            boolean first = true;
            long now = 0;
            while (!s.ended()) {
                if (s.phase() == Phase.RESOLVING) {
                    now = s.resolveUntil();
                    s = ENGINE.expire(s, now).newState();
                    continue;
                }
                int seat = s.turnSeat();
                ArtilleryAction action = seat == botSeat
                        ? ArtilleryBotPolicy.choose(s, seat)
                        : new ArtilleryAction.Fire(random.nextInt(61) - 30, random.nextInt(181),
                                20 + random.nextInt(81), Shell.PEA);
                int foeHp = s.unit(1 - botSeat).hp();
                s = ENGINE.apply(s, seat, action, now).newState();
                if (seat == botSeat && first) {
                    first = false;
                    if (s.unit(1 - botSeat).hp() < foeHp) {
                        firstShotHits++;
                    }
                }
                assertThat(ArtilleryInvariantChecker.check(s)).isEmpty();
            }
            if (s.result().winners().contains(botSeat)) {
                botWins++;
            }
        }
        System.out.printf("[artillery-bot] vs random: %d/%d wins, first-shot damage %d/%d%n",
                botWins, games, firstShotHits, games);
        assertThat(botWins).isGreaterThanOrEqualTo(games * 9 / 10);
    }

    @Test
    void one_move_stays_within_the_simulation_budget_and_time() {
        long worst = 0;
        long total = 0;
        int n = 0;
        for (long seed = 100; seed < 130; seed++) {
            ArtilleryState s = ENGINE.startMatch(seed, 4, List.of()).newState();
            for (int i = 0; i < 3; i++) {     // 데우기
                ArtilleryBotPolicy.choose(s, s.turnSeat());
            }
            long t0 = System.nanoTime();
            ArtilleryBotPolicy.choose(s, s.turnSeat());
            long dt = System.nanoTime() - t0;
            worst = Math.max(worst, dt);
            total += dt;
            n++;
            assertThat(ArtilleryBotPolicy.searchCost(s, s.turnSeat()).get(0)).isLessThanOrEqualTo(ArtilleryBotPolicy.SIM_BUDGET);
        }
        System.out.printf("[artillery-bot] move avg=%.1fms worst=%.1fms%n", total / 1e6 / n, worst / 1e6);
        assertThat(worst / 1e6).isLessThan(200);
    }
}
