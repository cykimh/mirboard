package com.mirboard.domain.game.artillery.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
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

    /** S4 (D-224) — 유닛을 섞어도 무작위보다 확실히 세고, 특수탄·아이템을 실제로 쓴다. 봇끼리 유닛별 승률도 찍는다(균형 참고). */
    @Test
    void with_units_and_items_the_bot_still_wins_and_spends_its_kit() {
        Random random = new Random(2026_11_10L);
        UnitKind[] kinds = UnitKind.values();
        int games = 60;
        int botWins = 0;
        Map<String, Integer> used = new TreeMap<>();
        for (int g = 0; g < games; g++) {
            int botSeat = g % 2;
            ArtilleryState s = ENGINE.startMatch(random.nextLong(), 2, List.of(),
                    List.of(kinds[g % 3], kinds[(g / 3) % 3])).newState();
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
                if (seat == botSeat && action instanceof ArtilleryAction.Fire f) {
                    assertThat(ArtilleryEngine.validFire(s, seat, f)).isTrue();
                    if (f.shell() != Shell.PEA) {
                        used.merge(f.shell().name(), 1, Integer::sum);
                    }
                    if (f.item() != null) {
                        used.merge(f.item().name(), 1, Integer::sum);
                    }
                }
                s = ENGINE.apply(s, seat, action, now).newState();
                assertThat(ArtilleryInvariantChecker.check(s)).isEmpty();
            }
            if (s.result().winners().contains(botSeat)) {
                botWins++;
            }
        }
        System.out.printf("[artillery-bot-s4] vs random (mixed units): %d/%d wins, used=%s%n", botWins, games, used);
        assertThat(botWins).isGreaterThanOrEqualTo(games * 9 / 10);
        assertThat(used.keySet()).contains("PUMPKIN");
        assertThat(used.keySet()).containsAnyOf("WATERING_CAN", "TWIN_SEED", "PINWHEEL");
        assertThat(used.keySet()).containsAnyOf("CHESTNUT", "SPORE", "VINE");

        // 봇끼리 — 유닛 짝마다 자리를 바꿔 가며
        Map<UnitKind, int[]> record = new EnumMap<>(UnitKind.class);
        for (UnitKind k : kinds) {
            record.put(k, new int[2]);
        }
        for (int g = 0; g < 54; g++) {
            UnitKind a = kinds[g % 3];
            UnitKind b = kinds[(g / 3) % 3];
            if (a == b) {
                continue;
            }
            ArtilleryState s = ENGINE.startMatch(random.nextLong(), 2, List.of(0, 1), List.of(a, b)).newState();
            long now = 0;
            while (!s.ended()) {
                if (s.phase() == Phase.RESOLVING) {
                    now = s.resolveUntil();
                    s = ENGINE.expire(s, now).newState();
                    continue;
                }
                s = ENGINE.apply(s, s.turnSeat(), ArtilleryBotPolicy.choose(s, s.turnSeat()), now).newState();
            }
            List<Integer> winners = s.result().winners();
            for (int seat = 0; seat < 2; seat++) {
                UnitKind k = seat == 0 ? a : b;
                record.get(k)[1]++;
                if (winners.size() == 1 && winners.contains(seat)) {
                    record.get(k)[0]++;
                }
            }
        }
        StringBuilder line = new StringBuilder();
        record.forEach((k, r) -> line.append(k).append(' ').append(r[0]).append('/').append(r[1]).append("  "));
        System.out.println("[artillery-bot-s4] bot vs bot wins by unit: " + line);
    }

    @Test
    void the_bot_picks_by_seed() {
        ArtilleryState s = ENGINE.startMatch(42L, 4, List.of(0, 1, 2, 3), 0L).newState();
        for (int seat = 0; seat < 4; seat++) {
            assertThat(ArtilleryBotPolicy.pick(s, seat)).isEqualTo(ArtilleryBotPolicy.pick(s, seat));
        }
        java.util.Set<UnitKind> seen = java.util.EnumSet.noneOf(UnitKind.class);
        for (long seed = 1; seed <= 30; seed++) {
            seen.add(ArtilleryBotPolicy.pick(ENGINE.startMatch(seed, 2, List.of(), 0L).newState(), 0));
        }
        assertThat(seen).containsExactlyInAnyOrder(UnitKind.values());
    }

    @Test
    void one_move_stays_within_the_simulation_budget_and_time() {
        long worst = 0;
        long total = 0;
        int n = 0;
        for (long seed = 100; seed < 130; seed++) {
            ArtilleryState s = ENGINE.startMatch(seed, 4, List.of(), List.of(UnitKind.DANDELION, UnitKind.ACORN,
                    UnitKind.SPROUT, UnitKind.DANDELION)).newState();
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
