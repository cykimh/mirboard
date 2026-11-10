package com.mirboard.domain.game.artillery;

import static com.mirboard.domain.game.artillery.ArtilleryFixtures.ENGINE;
import static com.mirboard.domain.game.artillery.ArtilleryFixtures.withUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.action.ArtilleryActionRejectedException;
import com.mirboard.domain.game.artillery.action.RejectionReason;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.physics.Ballistics;
import com.mirboard.domain.game.artillery.physics.Body;
import com.mirboard.domain.game.artillery.physics.Terrain;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryPublicView;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Crater;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.ShotReport;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** S4 (D-224) — 고르기 단계·유닛 3종·고유 특수탄·아이템 3종. 순수 엔진이라 Docker 불필요. */
class ArtilleryUnitsAndItemsTest {

    private static final long NOW = 1_000_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ArtilleryState started(long seed, UnitKind... kinds) {
        return ENGINE.startMatch(seed, kinds.length, List.of(), List.of(kinds)).newState();
    }

    private static ArtilleryState withWind(ArtilleryState s, int wind) {
        return new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), s.units(), s.craters(), s.phase(), s.turnSeat(),
                s.lastSeat(), s.turnNo(), wind, s.cloudLevel(), s.resolveUntil(), s.eliminations(), s.lastShot(),
                s.result(), s.pickUntil());
    }

    /** 차례 주인이 쏴서 대본이 조건을 만족하는 첫 (각도, 힘). */
    private static ArtilleryEngine.Result firstShot(ArtilleryState s, Shell shell, Item item,
                                                    Predicate<ShotReport> ok) {
        for (int angle = 20; angle <= 160; angle += 5) {
            for (int power = 20; power <= 100; power += 5) {
                ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(),
                        new ArtilleryAction.Fire(0, angle, power, shell, item), NOW);
                if (ok.test(((ArtilleryEvent.ShotResolved) r.events().getFirst()).report())) {
                    return r;
                }
            }
        }
        throw new AssertionError("no shot found");
    }

    private static ShotReport report(ArtilleryEngine.Result r) {
        return ((ArtilleryEvent.ShotResolved) r.events().getFirst()).report();
    }

    private static boolean hitsTerrain(ShotReport r) {
        return !r.stages().isEmpty() && r.stages().getFirst().shots().getFirst().end().equals("TERRAIN");
    }

    @Nested
    class Picking {

        @Test
        void the_match_starts_with_everyone_picking_at_once_and_kinds_stay_hidden() {
            ArtilleryEngine.Result r = ENGINE.startMatch(7L, 3, List.of(), NOW);
            ArtilleryState s = r.newState();
            assertThat(r.events()).isEmpty();
            assertThat(s.phase()).isEqualTo(Phase.PICKING);
            assertThat(ENGINE.pendingSeats(s)).containsExactly(0, 1, 2);
            assertThat(ENGINE.remainingMillis(s, NOW).orElseThrow()).isEqualTo(ArtilleryEngine.PICK_MS);
            assertThat(ENGINE.legalActions(s, 1)).hasSize(3).allMatch(a -> a instanceof ArtilleryAction.Pick);
            assertThat(ArtilleryInvariantChecker.check(s)).isEmpty();

            ArtilleryEngine.Result p = ENGINE.apply(s, 1, new ArtilleryAction.Pick(UnitKind.ACORN), NOW);
            assertThat(p.events()).containsExactly(new ArtilleryEvent.UnitPicked(1));
            ArtilleryState picked = p.newState();
            assertThat(ENGINE.pendingSeats(picked)).containsExactly(0, 2);
            ArtilleryPublicView view = ArtilleryPublicView.of(picked, 9_000L);
            assertThat(view.pickRemainingMs()).isEqualTo(9_000L);
            assertThat(view.units()).allMatch(u -> u.kind() == null && u.special() == null);
            assertThat(view.units().get(1).picked()).isTrue();
            assertThat(view.units().get(0).picked()).isFalse();
            assertThatThrownBy(() -> ENGINE.apply(picked, 1, new ArtilleryAction.Pick(UnitKind.SPROUT), NOW))
                    .extracting(e -> ((ArtilleryActionRejectedException) e).reason())
                    .isEqualTo(RejectionReason.NOT_YOUR_TURN);
            assertThatThrownBy(() -> ENGINE.apply(picked, 0, new ArtilleryAction.Fire(0, 45, 50, Shell.PEA), NOW))
                    .extracting(e -> ((ArtilleryActionRejectedException) e).reason())
                    .isEqualTo(RejectionReason.NOT_YOUR_TURN);
        }

        @Test
        void the_last_pick_reveals_everyone_equips_them_and_opens_the_first_turn() {
            ArtilleryState s = ENGINE.startMatch(7L, 3, List.of(), NOW).newState();
            s = ENGINE.apply(s, 0, new ArtilleryAction.Pick(UnitKind.DANDELION), NOW).newState();
            s = ENGINE.apply(s, 1, new ArtilleryAction.Pick(UnitKind.ACORN), NOW).newState();
            ArtilleryEngine.Result last = ENGINE.apply(s, 2, new ArtilleryAction.Pick(UnitKind.SPROUT), NOW);
            assertThat(last.events()).hasSize(3);
            assertThat(last.events().get(0)).isEqualTo(new ArtilleryEvent.UnitPicked(2));
            ArtilleryEvent.UnitsRevealed revealed = (ArtilleryEvent.UnitsRevealed) last.events().get(1);
            assertThat(revealed.units()).extracting(ArtilleryPublicView.UnitView::kind)
                    .containsExactly(UnitKind.DANDELION, UnitKind.ACORN, UnitKind.SPROUT);
            assertThat(last.events().get(2)).isInstanceOf(ArtilleryEvent.TurnChanged.class);
            ArtilleryState a = last.newState();
            assertThat(a.phase()).isEqualTo(Phase.AIMING);
            assertThat(a.units()).extracting(Unit::hp).containsExactly(85, 120, 100);
            assertThat(a.units()).allMatch(u -> u.specials() == 2 && u.items().equals(Item.starterKit()));
            // 같은 시드면 첫 차례도 고르기 없이 시작한 판과 같다.
            assertThat(a.turnSeat()).isEqualTo(started(7L, UnitKind.DANDELION, UnitKind.ACORN, UnitKind.SPROUT).turnSeat());
            assertThat(ArtilleryInvariantChecker.check(a)).isEmpty();
        }

        @Test
        void when_time_runs_out_the_unpicked_get_the_sprout() {
            ArtilleryState s = ENGINE.startMatch(9L, 2, List.of(), NOW).newState();
            s = ENGINE.apply(s, 1, new ArtilleryAction.Pick(UnitKind.DANDELION), NOW + 3_000).newState();
            assertThat(ENGINE.remainingMillis(s, NOW + 3_000).orElseThrow()).isEqualTo(12_000L);
            ArtilleryEngine.Result r = ENGINE.expire(s, NOW + ArtilleryEngine.PICK_MS);
            assertThat(r.newState().phase()).isEqualTo(Phase.AIMING);
            assertThat(r.newState().units()).extracting(Unit::kind).containsExactly(UnitKind.SPROUT, UnitKind.DANDELION);
        }

        @Test
        void desertion_while_picking_eliminates_and_may_finish_picking_or_the_match() {
            ArtilleryState s = ENGINE.startMatch(9L, 3, List.of(), NOW).newState();
            s = ENGINE.apply(s, 0, new ArtilleryAction.Pick(UnitKind.ACORN), NOW).newState();
            s = ENGINE.apply(s, 1, new ArtilleryAction.Pick(UnitKind.ACORN), NOW).newState();
            ArtilleryEngine.Desertion d = ENGINE.desert(s, 2);
            assertThat(d.outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.MATCH_CONTINUES);
            assertThat(d.newState().phase()).isEqualTo(Phase.AIMING);
            assertThat(d.newState().turnSeat()).isIn(0, 1);
            assertThat(ArtilleryInvariantChecker.check(d.newState())).isEmpty();

            ArtilleryState two = ENGINE.startMatch(9L, 2, List.of(), NOW).newState();
            ArtilleryEngine.Desertion end = ENGINE.desert(two, 0);
            assertThat(end.outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.MATCH_ENDED);
            assertThat(end.newState().result().winners()).containsExactly(1);
        }
    }

    @Nested
    class Units {

        @Test
        void wind_drift_scales_with_the_unit_and_the_pinwheel_removes_it() {
            Terrain empty = new Terrain();
            int[] landed = new int[3];
            int[] percents = {60, 100, 140};
            for (int i = 0; i < 3; i++) {
                Ballistics.Trace tr = Ballistics.fire(empty, List.of(), 0, 200, 200, 60, 40,
                        Ballistics.windAccel(8, percents[i]), 360, false);
                landed[i] = tr.impactX();
            }
            Ballistics.Trace calm = Ballistics.fire(empty, List.of(), 0, 200, 200, 60, 40, 0L, 360, false);
            assertThat(landed[2]).isLessThan(640);
            assertThat(landed[0]).isGreaterThan(calm.impactX());
            assertThat(landed[1]).isGreaterThan(landed[0]);
            assertThat(landed[2]).isGreaterThan(landed[1]);
            // 0 쪽으로 버림 — 좌우 대칭
            assertThat(Ballistics.windAccel(-7, 60)).isEqualTo(-Ballistics.windAccel(7, 60));
            assertThat(Ballistics.windAccel(5, 100)).isEqualTo(5 * 46);
        }

        @Test
        void the_pinwheel_shot_flies_exactly_like_a_calm_day_shot() {
            ArtilleryState base = started(11L, UnitKind.DANDELION, UnitKind.ACORN);
            ArtilleryState windy = withWind(base, 9);
            ArtilleryState calm = withWind(base, 0);
            ArtilleryAction.Fire aim = new ArtilleryAction.Fire(0, 60, 60, Shell.PEA);
            ShotReport pinwheel = report(ENGINE.apply(windy, windy.turnSeat(),
                    new ArtilleryAction.Fire(0, 60, 60, Shell.PEA, Item.PINWHEEL), NOW));
            ShotReport noWind = report(ENGINE.apply(calm, calm.turnSeat(), aim, NOW));
            ShotReport blown = report(ENGINE.apply(windy, windy.turnSeat(), aim, NOW));
            assertThat(pinwheel.stages().getFirst().shots().getFirst().path())
                    .isEqualTo(noWind.stages().getFirst().shots().getFirst().path())
                    .isNotEqualTo(blown.stages().getFirst().shots().getFirst().path());
            assertThat(pinwheel.item()).isEqualTo(Item.PINWHEEL);
        }

        @Test
        void fuel_depends_on_the_unit() {
            for (long seed = 1; seed <= 30; seed++) {
                ArtilleryState s = started(seed, UnitKind.ACORN, UnitKind.DANDELION);
                for (int seat = 0; seat < 2; seat++) {
                    ArtilleryState mine = s.turnSeat() == seat ? s
                            : ENGINE.apply(s, s.turnSeat(), new ArtilleryAction.Skip(), NOW).newState();
                    UnitKind kind = mine.unit(seat).kind();
                    for (int dir : new int[] {-1, 1}) {
                        ShotReport r = report(ENGINE.apply(mine, seat,
                                new ArtilleryAction.Fire(dir * ArtilleryAction.MAX_MOVE, 90, 0, Shell.PEA), NOW));
                        int steps = r.move().path().size() / 2;
                        assertThat(steps).isLessThanOrEqualTo(kind.fuel());
                        assertThat(r.move().fuelLeft()).isBetween(0, kind.fuel());
                    }
                }
            }
            assertThat(ArtilleryAction.MAX_MOVE).isEqualTo(130);
        }

        @Test
        void only_your_own_special_shell_and_only_twice() {
            ArtilleryState s = started(13L, UnitKind.SPROUT, UnitKind.ACORN);
            int me = s.turnSeat();
            Shell mine = s.unit(me).kind().special();
            Shell other = mine == Shell.VINE ? Shell.CHESTNUT : Shell.VINE;
            assertThatThrownBy(() -> ENGINE.apply(s, me, new ArtilleryAction.Fire(0, 60, 50, other), NOW))
                    .extracting(e -> ((ArtilleryActionRejectedException) e).reason()).isEqualTo(RejectionReason.NO_AMMO);
            assertThatThrownBy(() -> ENGINE.apply(s, me, new ArtilleryAction.Fire(0, 60, 50, Shell.SPORE), NOW))
                    .extracting(e -> ((ArtilleryActionRejectedException) e).reason()).isEqualTo(RejectionReason.NO_AMMO);
            ArtilleryState once = ENGINE.apply(s, me, new ArtilleryAction.Fire(0, 60, 50, mine), NOW).newState();
            assertThat(once.unit(me).specials()).isEqualTo(1);
            ArtilleryState empty = withUnit(s, s.unit(me).spend(mine).spend(mine));
            assertThatThrownBy(() -> ENGINE.apply(empty, me, new ArtilleryAction.Fire(0, 60, 50, mine), NOW))
                    .extracting(e -> ((ArtilleryActionRejectedException) e).reason()).isEqualTo(RejectionReason.NO_AMMO);
        }
    }

    @Nested
    class Specials {

        @Test
        void the_vine_shell_builds_a_mound_but_never_buries_a_unit() {
            ArtilleryState s = started(21L, UnitKind.SPROUT, UnitKind.SPROUT);
            int before = ArtilleryEngine.terrainOf(s).solidCount();
            ArtilleryEngine.Result r = firstShot(s, Shell.VINE, null, ArtilleryUnitsAndItemsTest::hitsTerrain);
            ArtilleryState after = r.newState();
            Crater op = after.craters().getLast();
            assertThat(op.fill()).isTrue();
            assertThat(op.r()).isEqualTo(Shell.VINE.craterRadius());
            assertThat(op.spare()).hasSize(4);
            Terrain t = ArtilleryEngine.terrainOf(after);
            assertThat(t.solidCount()).isGreaterThan(before);
            for (Unit u : after.aliveUnits()) {
                for (int y = u.footY() - Body.HEIGHT + 1; y <= u.footY(); y++) {
                    assertThat(t.solid(u.x(), y)).as("seat %d body cell y=%d", u.seat(), y).isFalse();
                }
            }
            assertThat(ArtilleryInvariantChecker.check(after)).isEmpty();
        }

        @Test
        void a_fill_spares_the_listed_bodies() {
            Terrain t = new Terrain();
            t.fill(100, 100, 16, List.of(100, 100), Crater.SPARE_RADIUS);
            assertThat(t.solid(100, 100)).isFalse();
            assertThat(t.solid(100 + 7, 100)).isFalse();
            assertThat(t.solid(100 + 8, 100)).isTrue();
            assertThat(t.solid(100, 100 + 16)).isTrue();
            assertThat(t.solid(100, 100 + 17)).isFalse();
        }

        @Test
        void the_chestnut_digs_a_big_crater() {
            ArtilleryState s = started(21L, UnitKind.ACORN, UnitKind.ACORN);
            ArtilleryEngine.Result r = firstShot(s, Shell.CHESTNUT, null, ArtilleryUnitsAndItemsTest::hitsTerrain);
            Crater op = r.newState().craters().getLast();
            assertThat(op.fill()).isFalse();
            assertThat(op.r()).isEqualTo(34);
        }

        @Test
        void the_spore_splits_into_three_seeds_at_the_top() {
            ArtilleryState s = started(21L, UnitKind.DANDELION, UnitKind.DANDELION);
            ArtilleryEngine.Result r = firstShot(s, Shell.SPORE, null,
                    rep -> rep.stages().getFirst().shots().getFirst().end().equals("SPLIT")
                            && rep.stages().getFirst().shots().stream().filter(x -> x.crater() != null).count() >= 2);
            List<ShotReport.Shot> shots = report(r).stages().getFirst().shots();
            assertThat(shots).hasSize(4);
            ShotReport.Shot parent = shots.getFirst();
            assertThat(parent.crater()).isNull();
            for (ShotReport.Shot child : shots.subList(1, 4)) {
                assertThat(child.startTick()).isEqualTo(parent.ticks());
                assertThat(child.path().subList(0, 2)).isEqualTo(parent.path().subList(parent.path().size() - 2,
                        parent.path().size()));
                if (child.crater() != null) {
                    assertThat(child.crater().r()).isEqualTo(Shell.SPORE.craterRadius());
                }
            }
            // 세 갈래는 가운데·왼쪽·오른쪽으로 벌어진다.
            assertThat(shots.get(1).impactX()).isLessThanOrEqualTo(shots.get(2).impactX());
            assertThat(shots.get(2).impactX()).isLessThanOrEqualTo(shots.get(3).impactX());
            long craters = shots.stream().filter(x -> x.crater() != null).count();
            assertThat(r.newState().craters()).hasSize((int) craters);
            assertThat(ArtilleryInvariantChecker.check(r.newState())).isEmpty();
        }
    }

    @Nested
    class Items {

        @Test
        void the_watering_can_heals_up_to_the_max_and_is_used_up() {
            ArtilleryState s = started(31L, UnitKind.ACORN, UnitKind.SPROUT);
            int me = s.turnSeat();
            ArtilleryState hurt = withUnit(s, s.unit(me).withHp(s.unit(me).maxHp() - 10));
            ArtilleryEngine.Result r = ENGINE.apply(hurt, me,
                    new ArtilleryAction.Fire(0, 90, 0, Shell.PEA, Item.WATERING_CAN), NOW);
            ShotReport rep = report(r);
            assertThat(rep.heal()).isNotNull();
            assertThat(rep.heal().amount()).isEqualTo(10);
            assertThat(rep.heal().hpAfter()).isEqualTo(hurt.unit(me).maxHp());
            ArtilleryState after = r.newState();
            assertThat(after.unit(me).items()).doesNotContain(Item.WATERING_CAN).hasSize(2);

            ArtilleryState low = withUnit(s, s.unit(me).withHp(40));
            assertThat(report(ENGINE.apply(low, me,
                    new ArtilleryAction.Fire(0, 45, 60, Shell.PEA, Item.WATERING_CAN), NOW)).heal().amount())
                    .isEqualTo(Item.HEAL);

            ArtilleryState used = withUnit(s, s.unit(me).without(Item.WATERING_CAN));
            assertThatThrownBy(() -> ENGINE.apply(used, me,
                    new ArtilleryAction.Fire(0, 45, 60, Shell.PEA, Item.WATERING_CAN), NOW))
                    .extracting(e -> ((ArtilleryActionRejectedException) e).reason()).isEqualTo(RejectionReason.NO_ITEM);
        }

        @Test
        void the_twin_seed_fires_a_second_volley_after_the_first_resolves() {
            ArtilleryState s = started(31L, UnitKind.SPROUT, UnitKind.ACORN, UnitKind.DANDELION);
            int me = s.turnSeat();
            ArtilleryEngine.Result r = firstShot(s, Shell.PUMPKIN, Item.TWIN_SEED,
                    rep -> rep.stages().size() == 2 && hitsTerrain(rep));
            ShotReport rep = report(r);
            assertThat(rep.stages()).hasSize(2);
            assertThat(r.newState().unit(me).pumpkins()).isEqualTo(1);     // 탄은 한 발만
            assertThat(r.newState().unit(me).items()).doesNotContain(Item.TWIN_SEED);
            long single = report(ENGINE.apply(s, me, new ArtilleryAction.Fire(0, rep.angle(), rep.power(),
                    Shell.PUMPKIN), NOW)).durationMs();
            assertThat(rep.durationMs()).isGreaterThan(single);
            // 두 번째 발은 첫 발이 바꾼 지형에서 — 구멍이 둘 이상 쌓인다.
            assertThat(r.newState().craters().size()).isGreaterThanOrEqualTo(1);
            assertThat(ArtilleryInvariantChecker.check(r.newState())).isEmpty();
        }

        @Test
        void one_item_per_turn_and_skip_keeps_the_items() {
            ArtilleryState s = started(31L, UnitKind.SPROUT, UnitKind.SPROUT);
            int me = s.turnSeat();
            ArtilleryState skipped = ENGINE.apply(s, me, new ArtilleryAction.Skip(), NOW).newState();
            assertThat(skipped.unit(me).items()).hasSize(3);
        }
    }

    @Test
    void new_state_survives_json_and_old_json_reads_as_a_sprout() throws Exception {
        ArtilleryState picking = ENGINE.startMatch(5L, 3, List.of(2), NOW).newState();
        picking = ENGINE.apply(picking, 0, new ArtilleryAction.Pick(UnitKind.ACORN), NOW).newState();
        assertThat(JSON.readValue(JSON.writeValueAsString(picking), ArtilleryState.class)).isEqualTo(picking);
        ArtilleryState s = started(5L, UnitKind.DANDELION, UnitKind.SPROUT, UnitKind.ACORN);
        ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(),
                new ArtilleryAction.Fire(0, 70, 60, Shell.PUMPKIN, Item.TWIN_SEED), NOW);
        assertThat(JSON.readValue(JSON.writeValueAsString(r.newState()), ArtilleryState.class)).isEqualTo(r.newState());
        for (ArtilleryEvent e : r.events()) {
            assertThat(JSON.readValue(JSON.writeValueAsString(e), ArtilleryEvent.class)).isEqualTo(e);
        }
        Unit old = JSON.readValue("{\"seat\":1,\"x\":10,\"footY\":20,\"hp\":70,\"pumpkins\":1,\"out\":null}", Unit.class);
        assertThat(old.kindOrDefault()).isEqualTo(UnitKind.SPROUT);
        assertThat(old.items()).isEmpty();
        assertThat(old.ammo(Shell.VINE)).isZero();
        Crater hole = JSON.readValue("{\"x\":1,\"y\":2,\"r\":14}", Crater.class);
        assertThat(hole).isEqualTo(new Crater(1, 2, 14));
    }
}
