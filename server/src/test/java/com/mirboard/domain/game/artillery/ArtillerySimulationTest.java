package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 무작위 행동 시뮬레이션 (`docs/rules-artillery.md` §12): 2~6인 각 200판(합 1,000판)이 전부 차례 상한 안에서 끝나고, 매 전이 뒤
 * 불변식이 지켜진다. 무작위 발사·이동·넘김에 가끔 탈주를 섞는다. S4(D-224)부터 고르기 단계·유닛 3종·특수탄·아이템도 섞는다.
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
        int items = 0;
        int specials = 0;
        Map<UnitKind, Integer> kinds = new EnumMap<>(UnitKind.class);
        for (int game = 0; game < GAMES; game++) {
            long now = 1_000_000L;
            // S4 (D-224) — 고르기 단계부터: 몇 명은 고르고 나머지는 시간 초과(새싹포)
            ArtilleryState s = ENGINE.startMatch(random.nextLong(), seats, List.of(), now).newState();
            assertInvariants(s, game);
            for (int seat = 0; seat < seats; seat++) {
                if (random.nextInt(4) > 0) {
                    UnitKind kind = UnitKind.values()[random.nextInt(UnitKind.values().length)];
                    s = ENGINE.apply(s, seat, new ArtilleryAction.Pick(kind), now).newState();
                    assertInvariants(s, game);
                }
            }
            if (s.phase() == Phase.PICKING) {
                now = s.pickUntil();
                s = ENGINE.expire(s, now).newState();
            }
            for (Unit u : s.units()) {
                kinds.merge(u.kind(), 1, Integer::sum);
            }
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
                        Unit me = s.unit(seat);
                        Shell shell = Shell.PEA;
                        int pickShell = random.nextInt(6);
                        if (pickShell == 0 && me.pumpkins() > 0) {
                            shell = Shell.PUMPKIN;
                        } else if (pickShell == 1 && me.specials() > 0) {
                            shell = me.kind().special();
                        }
                        Item item = !me.items().isEmpty() && random.nextInt(6) == 0
                                ? me.items().get(random.nextInt(me.items().size())) : null;
                        action = new ArtilleryAction.Fire(random.nextInt(81) - 40, random.nextInt(181),
                                20 + random.nextInt(81), shell, item);
                        if (item != null) {
                            items++;
                        }
                        if (shell.special()) {
                            specials++;
                        }
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
        System.out.printf("[artillery-sim] seats=%d games=%d avgTurns=%.1f reasons=%s avgFire=%.3fms kinds=%s items=%d specials=%d%n",
                seats, GAMES, totalTurns / (double) GAMES, reasons, fireNanos / 1e6 / Math.max(1, fires), kinds,
                items, specials);
    }

    private static void assertInvariants(ArtilleryState s, int game) {
        assertThat(ArtilleryInvariantChecker.check(s)).as("game %d turn %d", game, s.turnNo()).isEmpty();
    }
}
