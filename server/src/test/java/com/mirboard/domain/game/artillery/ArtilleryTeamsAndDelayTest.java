package com.mirboard.domain.game.artillery;

import static com.mirboard.domain.game.artillery.ArtilleryFixtures.ENGINE;
import static com.mirboard.domain.game.artillery.ArtilleryFixtures.startDelay;
import static com.mirboard.domain.game.artillery.ArtilleryFixtures.startTeams;
import static com.mirboard.domain.game.artillery.ArtilleryFixtures.withUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryPublicView;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.TurnOrder;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 2:2 팀전(D-225)과 포트리스식 딜레이 차례(D-226). */
class ArtilleryTeamsAndDelayTest {

    private static final long NOW = 1_000_000L;

    private static ArtilleryState settle(ArtilleryEngine.Result r) {
        ArtilleryState s = r.newState();
        if (s.phase() == Phase.RESOLVING) {
            s = ENGINE.expire(s, s.resolveUntil()).newState();
        }
        assertThat(ArtilleryInvariantChecker.check(s)).isEmpty();
        return s;
    }

    // ---------- 딜레이 (D-226) ----------

    @Test
    void the_first_round_follows_seat_order_from_the_seeded_first_seat() {
        ArtilleryState s = startDelay(7L, 4);
        int first = s.turnSeat();
        for (int k = 0; k < 4; k++) {
            assertThat(s.unit((first + k) % 4).delay()).isEqualTo(k);
        }
        assertThat(ArtilleryEngine.turnQueue(s)).containsExactly(first, (first + 1) % 4, (first + 2) % 4, (first + 3) % 4);
        assertThat(s.turnOrder()).isEqualTo(TurnOrder.DELAY);
        assertThat(s.teams()).isFalse();
    }

    @Test
    void each_turn_adds_shell_move_and_item_delay_and_skip_adds_eighty() {
        ArtilleryState s = startDelay(7L, 4);
        int a = s.turnSeat();
        int before = s.unit(a).delay();
        // 콩알, 이동 없음 → +100
        s = settle(ENGINE.apply(s, a, new ArtilleryAction.Fire(0, 90, 1, Shell.PEA), NOW));
        assertThat(s.unit(a).delay()).isEqualTo(before + 100);

        int b = s.turnSeat();
        int db = s.unit(b).delay();
        s = settle(ENGINE.apply(s, b, new ArtilleryAction.Skip(), NOW));
        assertThat(s.unit(b).delay()).isEqualTo(db + ArtilleryEngine.SKIP_DELAY);

        int c = s.turnSeat();
        int dc = s.unit(c).delay();
        ArtilleryEngine.Result r = ENGINE.apply(s, c, new ArtilleryAction.Fire(20, 90, 1, Shell.PUMPKIN, Item.TWIN_SEED), NOW);
        int fuelUsed = s.unit(c).kindOrDefault().fuel()
                - ((ArtilleryEvent.ShotResolved) r.events().getFirst()).report().move().fuelLeft();
        assertThat(r.newState().unit(c).delay())
                .isEqualTo(dc + Shell.PUMPKIN.delay() + fuelUsed / ArtilleryEngine.FUEL_PER_DELAY + Item.TWIN_SEED.delay());
        assertThat(Shell.PEA.delay()).isEqualTo(100);
        assertThat(Shell.PUMPKIN.delay()).isEqualTo(130);
        assertThat(Shell.VINE.delay()).isEqualTo(140);
        assertThat(Item.WATERING_CAN.delay()).isEqualTo(30);
        assertThat(Item.PINWHEEL.delay()).isEqualTo(30);
    }

    @Test
    void the_lowest_delay_goes_next_and_ties_go_in_seat_order_after_the_last_shooter() {
        ArtilleryState s = startDelay(7L, 3);
        // 좌석 0 을 아주 무겁게, 1·2 는 같게 — 좌석 2 가 방금 쐈다면 다음은 1 이 아니라 2 다음 좌석 순서의 1.
        s = withUnit(s, s.unit(0).withDelay(500));
        s = withUnit(s, s.unit(1).withDelay(300));
        s = withUnit(s, s.unit(2).withDelay(200));
        s = new ArtilleryState(s.seed(), 3, s.botSeats(), s.units(), s.craters(), Phase.AIMING, 2, 2, 5, 0,
                s.cloudLevel(), 0, 0, null, null, 0L, false, TurnOrder.DELAY);
        // 2 가 넘김 → 280. 다음 = 1(300)? 아니다 — 2 가 280 으로 여전히 가장 작다.
        s = settle(ENGINE.apply(s, 2, new ArtilleryAction.Skip(), NOW));
        assertThat(s.turnSeat()).isEqualTo(2);
        s = settle(ENGINE.apply(s, 2, new ArtilleryAction.Skip(), NOW));   // 360 → 다음은 1(300)
        assertThat(s.turnSeat()).isEqualTo(1);
        s = settle(ENGINE.apply(s, 1, new ArtilleryAction.Skip(), NOW));   // 1=380, 2=360 → 2
        assertThat(s.turnSeat()).isEqualTo(2);
        s = settle(ENGINE.apply(s, 2, new ArtilleryAction.Skip(), NOW));   // 2=440, 1=380 → 1
        s = settle(ENGINE.apply(s, 1, new ArtilleryAction.Skip(), NOW));   // 1=460, 2=440 → 2
        s = settle(ENGINE.apply(s, 2, new ArtilleryAction.Skip(), NOW));   // 2=520, 1=460 → 1
        s = settle(ENGINE.apply(s, 1, new ArtilleryAction.Skip(), NOW));   // 1=540, 0=500, 2=520 → 0
        assertThat(s.turnSeat()).isZero();
        // 동률: 0 을 2 와 같게 만들고 1 이 방금 쐈다면 1 다음 좌석 순서로 2 → 0 이 아니라 2.
        s = withUnit(s, s.unit(0).withDelay(520));
        s = new ArtilleryState(s.seed(), 3, s.botSeats(), s.units(), s.craters(), Phase.AIMING, 1, 1, s.turnNo(),
                0, s.cloudLevel(), 0, 0, null, null, 0L, false, TurnOrder.DELAY);
        s = withUnit(s, s.unit(1).withDelay(440));   // 넘기면 520 — 세 좌석이 520 동률, 1 다음 좌석 순서 → 2
        s = settle(ENGINE.apply(s, 1, new ArtilleryAction.Skip(), NOW));
        assertThat(s.turnSeat()).isEqualTo(2);
        assertThat(ArtilleryEngine.turnQueue(s)).containsExactly(2, 0, 1);
    }

    @Test
    void seat_order_states_from_before_d226_keep_seat_order_and_zero_delay() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ArtilleryState legacy = ArtilleryFixtures.start(7L, 3);
        ObjectNode node = (ObjectNode) json.readTree(json.writeValueAsString(legacy));
        node.remove("teams");
        node.remove("turnOrder");
        node.withArray("units").forEach(u -> ((ObjectNode) u).remove("delay"));
        ArtilleryState back = json.treeToValue(node, ArtilleryState.class);
        assertThat(back.turnOrder()).isEqualTo(TurnOrder.SEAT);
        assertThat(back.teams()).isFalse();
        int seat = back.turnSeat();
        ArtilleryState next = settle(ENGINE.apply(back, seat, new ArtilleryAction.Fire(0, 90, 1, Shell.PEA), NOW));
        assertThat(next.turnSeat()).isEqualTo((seat + 1) % 3);
        assertThat(next.units()).allMatch(u -> u.delay() == 0);
    }

    // ---------- 팀전 (D-225) ----------

    @Test
    void teams_need_four_or_six_seats() {
        assertThatThrownBy(() -> ENGINE.startMatch(1L, 3, List.of(), ArtilleryEngine.Setup.TEAMS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ENGINE.startMatch(1L, 2, List.of(), ArtilleryEngine.Setup.TEAMS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ENGINE.startMatch(1L, 6, List.of(), ArtilleryEngine.Setup.TEAMS).newState().teams()).isTrue();
    }

    @Test
    void the_first_round_alternates_teams_and_the_view_shows_teams_and_delays() {
        ArtilleryState s = startTeams(11L);
        List<Integer> queue = ArtilleryEngine.turnQueue(s);
        for (int i = 1; i < queue.size(); i++) {
            assertThat(queue.get(i) % 2).isNotEqualTo(queue.get(i - 1) % 2);
        }
        ArtilleryPublicView view = ArtilleryPublicView.of(s, null);
        assertThat(view.teams()).isTrue();
        assertThat(view.queue()).isEqualTo(queue);
        assertThat(view.units()).extracting(ArtilleryPublicView.UnitView::team).containsExactly(0, 1, 0, 1);
        assertThat(view.delays().shells()).containsEntry(Shell.PEA, 100);
        assertThat(view.delays().skip()).isEqualTo(80);
    }

    @Test
    void one_member_down_does_not_end_the_match_but_a_whole_team_does() {
        ArtilleryState s = startTeams(11L);
        ArtilleryEngine.Desertion d = ENGINE.desert(s, 1);
        assertThat(d.outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.MATCH_CONTINUES);
        d = ENGINE.desert(d.newState(), 3);
        assertThat(d.outcome()).isEqualTo(ArtilleryEngine.DesertOutcome.MATCH_ENDED);
        MatchResult r = d.newState().result();
        assertThat(r.teams()).isTrue();
        assertThat(r.winnerTeam()).isZero();
        assertThat(r.reason()).isEqualTo(MatchResult.EndReason.LAST_STANDING);
        assertThat(r.winners()).containsExactly(0, 2);
        assertThat(r.rankOf(0)).isEqualTo(1);
        assertThat(r.rankOf(2)).isEqualTo(1);
        assertThat(r.rankOf(1)).isEqualTo(3);
        assertThat(r.placements()).filteredOn(MatchResult.Placement::deserted).extracting(MatchResult.Placement::seat)
                .containsExactlyInAnyOrder(1, 3);
        assertThat(d.events()).anyMatch(e -> e instanceof ArtilleryEvent.MatchEnded m && m.teams()
                && Integer.valueOf(0).equals(m.winnerTeam()));
        assertThat(ArtilleryInvariantChecker.check(d.newState())).isEmpty();
    }

    @Test
    void team_placements_put_winners_first_losers_next_and_deserters_last_or_all_first_on_a_draw() {
        ArtilleryState s = startTeams(11L);
        List<Unit> units = List.of(s.unit(0), s.unit(1),
                s.unit(2).eliminated(new Unit.Elimination(1, Unit.Cause.DESERTED, 100)), s.unit(3));
        List<MatchResult.Placement> win1 = ArtilleryEngine.teamPlacements(units, 1);
        assertThat(win1).extracting(MatchResult.Placement::seat, MatchResult.Placement::rank)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 1), org.assertj.core.groups.Tuple.tuple(3, 1),
                        org.assertj.core.groups.Tuple.tuple(0, 3), org.assertj.core.groups.Tuple.tuple(2, 4));
        List<MatchResult.Placement> draw = ArtilleryEngine.teamPlacements(units, null);
        assertThat(draw).filteredOn(p -> !p.deserted()).allMatch(p -> p.rank() == 1);
        MatchResult drawn = new MatchResult(MatchResult.EndReason.ALL_DOWN, draw, true, null);
        assertThat(drawn.winners()).isEmpty();
        assertThat(drawn.draw()).isTrue();
    }

    @Test
    void when_both_teams_fall_together_the_higher_hp_sum_just_before_wins_and_equal_is_a_draw() {
        ArtilleryState s = startTeams(11L);
        // 좌석 0 은 먼저(order 1) 떨어졌고, 마지막 순간(order 2)에 1·2·3 이 함께 탈락: 팀 0 = 좌석 2 의 30, 팀 1 = 25 + 10.
        assertThat(ArtilleryEngine.teamOutcome(allDown(s, 30, 25, 10)).winnerTeam()).isEqualTo(1);
        assertThat(ArtilleryEngine.teamOutcome(allDown(s, 40, 25, 10)).winnerTeam()).isZero();
        MatchResult draw = ArtilleryEngine.teamOutcome(allDown(s, 35, 25, 10));
        assertThat(draw.reason()).isEqualTo(MatchResult.EndReason.ALL_DOWN);
        assertThat(draw.winnerTeam()).isNull();
        assertThat(draw.winners()).isEmpty();
        assertThat(draw.placements()).allMatch(p -> p.rank() == 1);
        // 아직 두 팀이 살아 있으면 안 끝난다.
        assertThat(ArtilleryEngine.teamOutcome(s)).isNull();
    }

    /** 좌석 0 은 order 1(직전 체력 90 — 마지막 순간이 아니라 세지 않는다), 1·2·3 은 order 2 에 각 직전 체력으로 탈락. */
    private static ArtilleryState allDown(ArtilleryState s, int hp2, int hp1, int hp3) {
        s = withUnit(s, s.unit(0).withHp(0).eliminated(new Unit.Elimination(1, Unit.Cause.KNOCKED_OUT, 90)));
        s = withUnit(s, s.unit(1).withHp(0).eliminated(new Unit.Elimination(2, Unit.Cause.KNOCKED_OUT, hp1)));
        s = withUnit(s, s.unit(2).withHp(0).eliminated(new Unit.Elimination(2, Unit.Cause.CLOUD_SEA, hp2)));
        s = withUnit(s, s.unit(3).withHp(0).eliminated(new Unit.Elimination(2, Unit.Cause.KNOCKED_OUT, hp3)));
        return new ArtilleryState(s.seed(), 4, List.of(), s.units(), s.craters(), Phase.RESOLVING, -1, 3, 4, 0,
                s.cloudLevel(), 1, 2, null, null, 0L, true, TurnOrder.DELAY);
    }

    @Test
    void no_humans_left_in_a_team_match_compares_alive_counts_then_hp() {
        ArtilleryState s = startTeams(11L, 1, 2, 3);
        ArtilleryEngine.Desertion d = ENGINE.desert(s, 0);
        MatchResult r = d.newState().result();
        assertThat(r.reason()).isEqualTo(MatchResult.EndReason.NO_HUMANS);
        assertThat(r.winnerTeam()).isEqualTo(1);   // 팀 1 둘 살아 있음 > 팀 0 하나
        assertThat(r.winners()).containsExactly(1, 3);
    }

    @Test
    void the_bot_never_targets_its_teammate() {
        ArtilleryState s = startTeams(11L, 0, 1, 2, 3);
        for (int seat = 0; seat < 4; seat++) {
            Unit t = com.mirboard.domain.game.artillery.bot.ArtilleryBotPolicyAccess.target(s, seat);
            assertThat(t.seat() % 2).isNotEqualTo(seat % 2);
        }
    }
}
