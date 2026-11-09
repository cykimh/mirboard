package com.mirboard.domain.game.artillery;

import static com.mirboard.domain.game.artillery.ArtilleryFixtures.ENGINE;
import static com.mirboard.domain.game.artillery.ArtilleryFixtures.directHit;
import static com.mirboard.domain.game.artillery.ArtilleryFixtures.start;
import static com.mirboard.domain.game.artillery.ArtilleryFixtures.withUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.action.ArtilleryActionRejectedException;
import com.mirboard.domain.game.artillery.action.RejectionReason;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 순수 엔진 — 차례·발사·연출 대기·피해·탈락·밀물·종료·탈주 (`docs/rules-artillery.md` §4~§11). */
class ArtilleryEngineTest {

    private static final long NOW = 1_000_000L;

    @Test
    void start_places_every_seat_and_opens_the_first_turn() {
        ArtilleryEngine.Result r = ENGINE.startMatch(7L, 4, List.of(3));
        ArtilleryState s = r.newState();
        assertThat(s.phase()).isEqualTo(Phase.AIMING);
        assertThat(s.turnNo()).isZero();
        assertThat(s.units()).hasSize(4).allMatch(u -> u.hp() == 100 && u.pumpkins() == 2 && u.alive());
        assertThat(s.units().stream().map(Unit::x).distinct()).hasSize(4);
        assertThat(r.events()).singleElement().isInstanceOf(ArtilleryEvent.TurnChanged.class);
        assertThat(ENGINE.pendingSeats(s)).containsExactly(s.turnSeat());
        assertThat(ArtilleryInvariantChecker.check(s)).isEmpty();
    }

    @Test
    void only_the_turn_owner_may_act_and_ranges_are_checked() {
        ArtilleryState s = start(7L, 3);
        int me = s.turnSeat();
        int other = (me + 1) % 3;
        assertThatThrownBy(() -> ENGINE.apply(s, other, new ArtilleryAction.Skip(), NOW))
                .isInstanceOf(ArtilleryActionRejectedException.class)
                .extracting(e -> ((ArtilleryActionRejectedException) e).reason())
                .isEqualTo(RejectionReason.NOT_YOUR_TURN);
        assertThatThrownBy(() -> ENGINE.apply(s, me, new ArtilleryAction.Fire(0, 181, 50, Shell.PEA), NOW))
                .extracting(e -> ((ArtilleryActionRejectedException) e).reason()).isEqualTo(RejectionReason.OUT_OF_RANGE);
        assertThatThrownBy(() -> ENGINE.apply(s, me, new ArtilleryAction.Fire(101, 45, 50, Shell.PEA), NOW))
                .extracting(e -> ((ArtilleryActionRejectedException) e).reason()).isEqualTo(RejectionReason.OUT_OF_RANGE);
        ArtilleryState empty = withUnit(s, s.unit(me).withPumpkins(0));
        assertThatThrownBy(() -> ENGINE.apply(empty, me, new ArtilleryAction.Fire(0, 45, 50, Shell.PUMPKIN), NOW))
                .extracting(e -> ((ArtilleryActionRejectedException) e).reason()).isEqualTo(RejectionReason.NO_AMMO);
    }

    @Test
    void fire_resolves_at_once_then_waits_then_the_next_seat_aims() {
        ArtilleryState s = start(7L, 3);
        int me = s.turnSeat();
        ArtilleryEngine.Result fired = ENGINE.apply(s, me, new ArtilleryAction.Fire(0, 60, 55, Shell.PUMPKIN), NOW);
        ArtilleryState waiting = fired.newState();
        assertThat(waiting.phase()).isEqualTo(Phase.RESOLVING);
        assertThat(ENGINE.pendingSeats(waiting)).isEmpty();
        assertThat(waiting.unit(me).pumpkins()).isEqualTo(1);
        assertThat(fired.events()).singleElement().isInstanceOf(ArtilleryEvent.ShotResolved.class);
        long remaining = ENGINE.remainingMillis(waiting, NOW).orElseThrow();
        assertThat(remaining).isBetween(600L, 18_000L);
    }

    @Test
    void nobody_may_act_while_resolving_and_expire_opens_the_next_turn() {
        ArtilleryState s = start(7L, 3);
        int me = s.turnSeat();
        ArtilleryState waiting = ENGINE.apply(s, me, new ArtilleryAction.Fire(0, 60, 55, Shell.PEA), NOW).newState();
        for (int seat = 0; seat < 3; seat++) {
            int fs = seat;
            assertThatThrownBy(() -> ENGINE.apply(waiting, fs, new ArtilleryAction.Skip(), NOW))
                    .extracting(e -> ((ArtilleryActionRejectedException) e).reason())
                    .isEqualTo(RejectionReason.NOT_YOUR_TURN);
        }
        ArtilleryEngine.Result next = ENGINE.expire(waiting, waiting.resolveUntil());
        assertThat(next.newState().phase()).isEqualTo(Phase.AIMING);
        assertThat(next.newState().turnSeat()).isEqualTo((me + 1) % 3);
        assertThat(next.newState().turnNo()).isEqualTo(1);
        assertThat(next.newState().wind()).isEqualTo(ArtilleryEngine.windAt(7L, 1));
        assertThat(next.events()).singleElement().isInstanceOf(ArtilleryEvent.TurnChanged.class);
        assertThat(ENGINE.expire(next.newState(), NOW)).isNull();
    }

    @Test
    void skip_and_timeout_pass_the_turn_without_waiting() {
        ArtilleryState s = start(11L, 2);
        int me = s.turnSeat();
        ArtilleryEngine.Result skipped = ENGINE.apply(s, me, new ArtilleryAction.Skip(), NOW);
        assertThat(skipped.newState().phase()).isEqualTo(Phase.AIMING);
        assertThat(skipped.newState().turnSeat()).isEqualTo(1 - me);
        assertThat(skipped.events().getFirst()).isEqualTo(new ArtilleryEvent.TurnSkipped(me, ArtilleryEvent.TurnSkipped.Reason.SKIP));
        ArtilleryEngine.Result timedOut = ENGINE.timeout(skipped.newState(), NOW);
        assertThat(timedOut.newState().turnSeat()).isEqualTo(me);
        assertThat(timedOut.events().getFirst())
                .isEqualTo(new ArtilleryEvent.TurnSkipped(1 - me, ArtilleryEvent.TurnSkipped.Reason.TIMEOUT));
    }

    @Test
    void a_direct_hit_that_takes_the_last_hp_ends_a_two_player_match() {
        ArtilleryState s = start(21L, 2);
        int me = s.turnSeat();
        int foe = 1 - me;
        s = withUnit(s, s.unit(foe).withHp(10));
        ArtilleryAction.Fire hit = directHit(s, foe, Shell.PEA);
        assertThat(hit).as("a direct-hit aim exists on this map").isNotNull();
        ArtilleryEngine.Result r = ENGINE.apply(s, me, hit, NOW);
        ArtilleryState end = r.newState();
        assertThat(end.phase()).isEqualTo(Phase.ENDED);
        assertThat(end.unit(foe).alive()).isFalse();
        assertThat(end.unit(foe).out().cause()).isIn(Unit.Cause.KNOCKED_OUT, Unit.Cause.CLOUD_SEA);
        assertThat(end.result().reason()).isEqualTo(MatchResult.EndReason.LAST_STANDING);
        assertThat(end.result().winners()).containsExactly(me);
        assertThat(r.events()).hasSize(2);
        assertThat(r.events().get(1)).isInstanceOf(ArtilleryEvent.MatchEnded.class);
        var report = ((ArtilleryEvent.ShotResolved) r.events().get(0)).report();
        assertThat(report.damages()).anyMatch(d -> d.seat() == foe && d.direct() && d.hpAfter() == 0);
        assertThat(report.eliminated()).anyMatch(e -> e.seat() == foe);
        assertThat(ArtilleryInvariantChecker.check(end)).isEmpty();
    }

    @Test
    void the_crater_is_kept_and_the_terrain_rebuilds_from_seed_and_craters() {
        ArtilleryState s = start(31L, 2);
        int before = ArtilleryEngine.terrainOf(s).solidCount();
        ArtilleryState after = ENGINE.apply(s, s.turnSeat(), new ArtilleryAction.Fire(0, 80, 30, Shell.PEA), NOW).newState();
        if (!after.craters().isEmpty()) {
            assertThat(ArtilleryEngine.terrainOf(after).solidCount()).isLessThan(before);
        }
        assertThat(ArtilleryEngine.terrainOf(after).hash()).isEqualTo(ArtilleryEngine.terrainOf(after).hash());
    }

    @Test
    void the_cloud_rises_after_ten_turns_per_seat_and_eventually_ends_the_match() {
        ArtilleryState s = start(41L, 2);
        int turns = 0;
        boolean rose = false;
        while (!s.ended()) {
            ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(), new ArtilleryAction.Skip(), NOW);
            s = r.newState();
            turns++;
            if (s.cloudLevel() < 360) {
                rose = true;
            }
            assertThat(ArtilleryInvariantChecker.check(s)).isEmpty();
            assertThat(turns).isLessThan(ArtilleryEngine.turnLimit(2));
        }
        assertThat(rose).isTrue();
        assertThat(turns).isGreaterThanOrEqualTo(20);
        assertThat(s.result().reason()).isIn(MatchResult.EndReason.LAST_STANDING, MatchResult.EndReason.ALL_DOWN);
        assertThat(s.units()).allMatch(u -> u.out() == null || u.out().cause() == Unit.Cause.CLOUD_SEA);
    }

    @Test
    void desertion_eliminates_and_passes_the_turn_when_it_was_theirs() {
        ArtilleryState s = start(51L, 3);
        int me = s.turnSeat();
        ArtilleryEngine.Desertion d = ENGINE.desert(s, me);
        assertThat(d.outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.MATCH_CONTINUES);
        assertThat(d.newState().unit(me).out().cause()).isEqualTo(Unit.Cause.DESERTED);
        assertThat(d.newState().turnSeat()).isNotEqualTo(me);
        assertThat(d.events().getFirst()).isInstanceOf(ArtilleryEvent.UnitEliminated.class);
        assertThat(ENGINE.desert(d.newState(), me).outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.NOT_APPLICABLE);
        ArtilleryEngine.Desertion second = ENGINE.desert(d.newState(), d.newState().turnSeat());
        assertThat(second.outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.MATCH_ENDED);
        MatchResult result = second.newState().result();
        assertThat(result.winners()).hasSize(1);
        assertThat(result.placements()).filteredOn(MatchResult.Placement::deserted).hasSize(2)
                .allMatch(p -> p.rank() == 2);
    }

    @Test
    void the_match_ends_when_only_bots_remain() {
        ArtilleryState s = start(61L, 3, 1, 2);
        ArtilleryEngine.Desertion d = ENGINE.desert(s, 0);
        assertThat(d.outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.MATCH_ENDED);
        assertThat(d.newState().result().reason()).isEqualTo(MatchResult.EndReason.NO_HUMANS);
    }

    @Test
    void an_all_bot_match_does_not_end_at_the_start() {
        ArtilleryState s = start(61L, 2, 0, 1);
        assertThat(s.phase()).isEqualTo(Phase.AIMING);
    }

    @Test
    void ranking_puts_later_and_healthier_eliminations_higher_and_deserters_last() {
        List<Unit> units = List.of(
                new Unit(0, 10, 10, 40, 2, null),
                new Unit(1, 20, 10, 0, 2, new Unit.Elimination(2, Unit.Cause.KNOCKED_OUT, 30)),
                new Unit(2, 30, 10, 0, 2, new Unit.Elimination(2, Unit.Cause.KNOCKED_OUT, 50)),
                new Unit(3, 40, 10, 0, 2, new Unit.Elimination(1, Unit.Cause.CLOUD_SEA, 90)),
                new Unit(4, 50, 10, 70, 2, new Unit.Elimination(3, Unit.Cause.DESERTED, 70)),
                new Unit(5, 60, 10, 0, 2, new Unit.Elimination(2, Unit.Cause.KNOCKED_OUT, 30)));
        List<MatchResult.Placement> p = ArtilleryEngine.placements(units);
        assertThat(p).extracting(MatchResult.Placement::seat, MatchResult.Placement::rank).containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, 1),
                org.assertj.core.groups.Tuple.tuple(2, 2),
                org.assertj.core.groups.Tuple.tuple(1, 3),
                org.assertj.core.groups.Tuple.tuple(5, 3),
                org.assertj.core.groups.Tuple.tuple(3, 5),
                org.assertj.core.groups.Tuple.tuple(4, 6));
    }

    @Test
    void legal_actions_are_a_sample_only_for_the_turn_owner() {
        ArtilleryState s = start(7L, 2);
        assertThat(ENGINE.legalActions(s, s.turnSeat())).hasSize(13 * 10 * 2 + 1);
        assertThat(ENGINE.legalActions(s, 1 - s.turnSeat())).isEmpty();
    }
}
