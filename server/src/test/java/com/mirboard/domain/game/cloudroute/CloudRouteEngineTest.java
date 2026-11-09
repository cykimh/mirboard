package com.mirboard.domain.game.cloudroute;

import static com.mirboard.domain.game.cloudroute.CloudRouteTables.ScriptedDice;
import static com.mirboard.domain.game.cloudroute.CloudRouteTables.engine;
import static com.mirboard.domain.game.cloudroute.CloudRouteTables.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.cloudroute.CloudRouteEngine.Desertion;
import com.mirboard.domain.game.cloudroute.CloudRouteEngine.Result;
import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.action.CloudRouteActionRejectedException;
import com.mirboard.domain.game.cloudroute.action.RejectionReason;
import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.AssetsLiquidated.Sale;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.LeaveMethod;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.MoneyReason;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent.MoveKind;
import com.mirboard.domain.game.cloudroute.invariant.CloudRouteInvariantChecker;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.MatchResult.EndReason;
import com.mirboard.domain.game.cloudroute.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.cloudroute.state.MatchResult.Standing;
import com.mirboard.domain.game.cloudroute.state.Pending;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** `docs/rules-cloudroute.md` §4~§12 — 규칙마다 정해 둔 주사위로 한 장면씩. */
class CloudRouteEngineTest {

    private static final CloudRouteAction ROLL = new CloudRouteAction.Roll();
    private static final CloudRouteAction DECLINE = new CloudRouteAction.Decline();
    private static final CloudRouteAction SKIP = new CloudRouteAction.Skip();

    private static <T extends CloudRouteEvent> List<T> of(Result r, Class<T> type) {
        return r.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Result apply(CloudRouteEngine e, CloudRouteState s, int seat, CloudRouteAction a) {
        CloudRouteInvariantChecker.check(s);
        Result r = e.apply(s, seat, a);
        CloudRouteInvariantChecker.check(r.newState());
        return r;
    }

    private static RejectionReason rejection(Runnable r) {
        try {
            r.run();
        } catch (CloudRouteActionRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("not rejected");
    }

    @Nested
    class Start {
        @Test
        void a_match_starts_with_full_cash_an_unowned_board_and_a_shuffled_deck() {
            CloudRouteEngine e = new CloudRouteEngine(CloudRouteTables.context(4), new Random(3));
            Result r = e.startMatch();
            CloudRouteState s = r.newState();
            CloudRouteInvariantChecker.check(s);
            assertThat(s.seats()).allMatch(x -> x.cash() == 1_500 && x.position() == 0);
            assertThat(s.owners()).containsOnly(-1);
            assertThat(EnumSet.copyOf(s.letterDeck())).isEqualTo(EnumSet.allOf(Letter.class));
            assertThat(s.turnSeat()).isEqualTo(s.startSeat());
            assertThat(s.roundLimit()).isEqualTo(20);
            assertThat(r.events()).containsExactly(new CloudRouteEvent.TurnChanged(s.turnSeat(), 1),
                    new CloudRouteEvent.DecisionRequired(s.turnSeat(), new Pending.AwaitRoll()));
        }

        @Test
        void the_match_length_option_sets_the_round_limit() {
            for (var entry : java.util.Map.of("SHORT", 14, "NORMAL", 20, "LONG", 28).entrySet()) {
                CloudRouteEngine e = new CloudRouteEngine(new com.mirboard.domain.game.core.GameContext("t",
                        List.of(1L, 2L, 3L, 4L), 0, 0, List.of(),
                        java.util.Map.of(com.mirboard.domain.game.core.RoomOption.MATCH_LENGTH, entry.getKey())), new Random(3));
                CloudRouteState s = e.startMatch().newState();
                assertThat(s.roundLimit()).as(entry.getKey()).isEqualTo(entry.getValue());
                CloudRouteInvariantChecker.check(s);
            }
            // 값이 없거나 모르면 보통.
            CloudRouteEngine plain = new CloudRouteEngine(CloudRouteTables.context(2), new Random(3));
            assertThat(plain.startMatch().newState().roundLimit()).isEqualTo(30);
            assertThat(com.mirboard.domain.game.cloudroute.board.Economy.MatchLength.of("WHATEVER"))
                    .isEqualTo(com.mirboard.domain.game.cloudroute.board.Economy.MatchLength.NORMAL);
        }

        @Test
        void five_seats_are_refused() {
            CloudRouteEngine e = new CloudRouteEngine(CloudRouteTables.context(5), new Random(3));
            assertThatThrownBy(e::startMatch).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class TurnsAndDoubles {
        @Test
        void passing_the_start_pays_the_salary_once() {
            CloudRouteState s = table(2).at(0, 20).build();
            Result r = apply(engine(2, new ScriptedDice(2, 3)), s, 0, ROLL);
            assertThat(of(r, CloudRouteEvent.TokenMoved.class))
                    .containsExactly(new CloudRouteEvent.TokenMoved(0, 20, 1, MoveKind.ROLL, 200, 1_700));
            assertThat(r.newState().pending()).isEqualTo(new Pending.AwaitPurchase(1, 2));
        }

        @Test
        void stopping_on_the_start_pays_the_salary_and_ends_the_turn() {
            CloudRouteState s = table(2).at(0, 19).build();
            Result r = apply(engine(2, new ScriptedDice(2, 3)), s, 0, ROLL);
            assertThat(r.newState().cash(0)).isEqualTo(1_700);
            assertThat(r.newState().turnSeat()).isEqualTo(1);
            assertThat(r.newState().round()).isEqualTo(1);
        }

        @Test
        void doubles_roll_again_in_the_same_turn() {
            ScriptedDice dice = new ScriptedDice(2, 2, 1, 2);
            CloudRouteEngine e = engine(2, dice);
            CloudRouteState s = apply(e, table(2).build(), 0, ROLL).newState();
            assertThat(s.pending()).isEqualTo(new Pending.AwaitPurchase(4, 2));
            s = apply(e, s, 0, DECLINE).newState();
            assertThat(s.turnSeat()).isZero();
            assertThat(s.pending()).isEqualTo(new Pending.AwaitRoll());
            assertThat(s.doublesThisTurn()).isEqualTo(1);
            s = apply(e, s, 0, ROLL).newState(); // 4 → 7
            s = apply(e, s, 0, DECLINE).newState();
            assertThat(s.turnSeat()).isEqualTo(1);
        }

        @Test
        void a_third_double_goes_straight_to_the_whirlpool_without_moving() {
            CloudRouteState s = table(2).at(0, 20).doubles(2).rollAgain().build();
            Result r = apply(engine(2, new ScriptedDice(1, 1)), s, 0, ROLL);
            assertThat(of(r, CloudRouteEvent.TokenMoved.class))
                    .containsExactly(new CloudRouteEvent.TokenMoved(0, 20, 6, MoveKind.WHIRLPOOL, 0, 1_500));
            assertThat(r.newState().seat(0).trapped()).isTrue();
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }

        @Test
        void landing_on_the_whirlpool_with_doubles_loses_the_extra_roll() {
            Result r = apply(engine(2, new ScriptedDice(3, 3)), table(2).build(), 0, ROLL);
            assertThat(r.newState().seat(0).trapped()).isTrue();
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }

        @Test
        void a_round_ends_when_the_turn_comes_back_to_the_start_seat() {
            CloudRouteState s = table(3).start(2).turn(1).at(1, 19).build();
            Result r = apply(engine(3, new ScriptedDice(2, 3)), s, 1, ROLL);
            assertThat(r.events()).contains(new CloudRouteEvent.TurnChanged(2, 2));
        }

        @Test
        void the_round_count_survives_the_start_seat_being_eliminated() {
            CloudRouteState s = table(3).start(0).turn(2).at(2, 19).eliminated(0, Elimination.Reason.DESERTED).build();
            Result r = apply(engine(3, new ScriptedDice(2, 3)), s, 2, ROLL);
            assertThat(r.events()).contains(new CloudRouteEvent.TurnChanged(1, 2));
        }

        @Test
        void the_last_turn_of_the_last_round_ends_the_match_by_total_assets() {
            CloudRouteState s = table(2).turn(1).round(30).at(1, 19).own(0, 23, 0).build();
            Result r = apply(engine(2, new ScriptedDice(2, 3)), s, 1, ROLL);
            CloudRouteState end = r.newState();
            assertThat(end.result().reason()).isEqualTo(EndReason.ROUND_LIMIT);
            assertThat(end.result().standings()).containsExactly(
                    new Standing(0, 1, 1_740, 1_500, SeatStatus.ALIVE),
                    new Standing(1, 2, 1_700, 1_700, SeatStatus.ALIVE));
            assertThat(end.pending()).isNull();
        }
    }

    @Nested
    class Lands {
        @Test
        void buying_with_buildings_pays_the_land_and_each_level() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 3));
            CloudRouteState s = apply(e, table(2).build(), 0, ROLL).newState();
            Result r = apply(e, s, 0, new CloudRouteAction.Buy(2));
            assertThat(of(r, CloudRouteEvent.LandBought.class))
                    .containsExactly(new CloudRouteEvent.LandBought(0, 4, 2, 160, 1_340));
            assertThat(r.newState().owners().get(4)).isZero();
            assertThat(r.newState().levels().get(4)).isEqualTo(2);
        }

        @Test
        void the_buildable_level_is_capped_by_cash() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 3));
            CloudRouteState s = apply(e, table(2).cash(0, 150).build(), 0, ROLL).newState();
            assertThat(s.pending()).isEqualTo(new Pending.AwaitPurchase(4, 1));
            CloudRouteState fixed = s;
            assertThat(rejection(() -> e.apply(fixed, 0, new CloudRouteAction.Buy(2)))).isEqualTo(RejectionReason.INVALID_LEVEL);
        }

        @Test
        void without_enough_cash_there_is_no_decision() {
            Result r = apply(engine(2, new ScriptedDice(1, 3)), table(2).cash(0, 50).build(), 0, ROLL);
            assertThat(r.newState().turnSeat()).isEqualTo(1);
            assertThat(r.newState().owners().get(4)).isEqualTo(-1);
        }

        @Test
        void revisiting_my_land_builds_several_levels_at_once_up_to_the_tower() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 3));
            CloudRouteState s = apply(e, table(2).own(0, 4, 1).build(), 0, ROLL).newState();
            assertThat(s.pending()).isEqualTo(new Pending.AwaitBuild(4, 2, 3));
            Result r = apply(e, s, 0, new CloudRouteAction.Build(3));
            assertThat(of(r, CloudRouteEvent.Built.class)).containsExactly(new CloudRouteEvent.Built(0, 4, 3, 80, 1_420));
        }

        @Test
        void a_tile_bought_this_turn_cannot_be_built_on_again_this_turn() {
            CloudRouteState s = table(2).own(0, 4, 0).locked(4).build();
            Result r = apply(engine(2, new ScriptedDice(1, 3)), s, 0, ROLL);
            assertThat(r.newState().turnSeat()).isEqualTo(1);
            assertThat(r.newState().lockedTiles()).isEmpty(); // 다음 차례에 풀린다
        }

        @Test
        void a_toll_is_paid_even_to_a_trapped_owner_and_then_takeover_is_offered() {
            CloudRouteState s = table(2).own(1, 7, 2).trapped(1, 0).build();
            Result r = apply(engine(2, new ScriptedDice(3, 4)), s, 0, ROLL);
            assertThat(of(r, CloudRouteEvent.TollPaid.class))
                    .containsExactly(new CloudRouteEvent.TollPaid(0, 1, 7, 140, false, false, 1_360, 1_640));
            assertThat(r.newState().pending()).isEqualTo(new Pending.AwaitTakeover(7, 1, 300));
        }

        @Test
        void a_monopoly_multiplies_by_one_and_a_half_rounded_to_ten() {
            CloudRouteState s = table(2).at(0, 12).own(1, 16, 0).own(1, 17, 0).build();
            Result r = apply(engine(2, new ScriptedDice(1, 3)), s, 0, ROLL);
            assertThat(of(r, CloudRouteEvent.TollPaid.class).getFirst().amount()).isEqualTo(50); // 30 × 1.5 = 45 → 50
        }

        @Test
        void a_festival_on_a_monopoly_triples_the_toll() {
            CloudRouteState s = table(2).at(0, 12).own(1, 16, 0).own(1, 17, 0).festival(16).build();
            Result r = apply(engine(2, new ScriptedDice(1, 3)), s, 0, ROLL);
            CloudRouteEvent.TollPaid toll = of(r, CloudRouteEvent.TollPaid.class).getFirst();
            assertThat(toll.amount()).isEqualTo(90);
            assertThat(toll.monopoly()).isTrue();
            assertThat(toll.festival()).isTrue();
        }

        @Test
        void a_takeover_moves_the_land_with_its_buildings_and_puts_out_its_festival() {
            CloudRouteState s = table(2).at(0, 7).cash(0, 1_360).own(1, 7, 2).festival(7)
                    .pending(new Pending.AwaitTakeover(7, 1, 300)).build();
            Result r = apply(engine(2, new ScriptedDice()), s, 0, new CloudRouteAction.Takeover());
            assertThat(of(r, CloudRouteEvent.TakenOver.class))
                    .containsExactly(new CloudRouteEvent.TakenOver(0, 1, 7, 300, 1_060, 1_800));
            assertThat(r.events()).contains(new CloudRouteEvent.FestivalCleared(7));
            assertThat(r.newState().owners().get(7)).isZero();
            assertThat(r.newState().levels().get(7)).isEqualTo(2);
            assertThat(r.newState().festivalTile()).isEqualTo(-1);
        }

        @Test
        void a_tower_cannot_be_taken_over() {
            CloudRouteState s = table(2).own(1, 7, 3).build();
            Result r = apply(engine(2, new ScriptedDice(3, 4)), s, 0, ROLL);
            assertThat(of(r, CloudRouteEvent.TollPaid.class).getFirst().amount()).isEqualTo(280);
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }
    }

    @Nested
    class SpecialTiles {
        @Test
        void customs_takes_a_hundred() {
            Result r = apply(engine(2, new ScriptedDice(1, 3)), table(2).at(0, 5).build(), 0, ROLL);
            assertThat(of(r, CloudRouteEvent.MoneyChanged.class))
                    .containsExactly(new CloudRouteEvent.MoneyChanged(0, -100, MoneyReason.CUSTOMS, 1_400));
        }

        @Test
        void a_festival_replaces_anyone_elses_and_skips_the_current_festival_tile() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 3));
            CloudRouteState s = table(2).at(0, 8).own(0, 1, 0).own(0, 2, 0).own(1, 4, 0).festival(2).build();
            s = apply(e, s, 0, ROLL).newState();
            assertThat(s.pending()).isEqualTo(new Pending.AwaitFestival(List.of(1)));
            Result r = apply(e, s, 0, new CloudRouteAction.HostFestival(1));
            assertThat(r.newState().festivalTile()).isEqualTo(1);
            CloudRouteState fixed = s;
            assertThat(rejection(() -> e.apply(fixed, 0, new CloudRouteAction.HostFestival(4))))
                    .isEqualTo(RejectionReason.INVALID_TILE);
        }

        @Test
        void a_flight_costs_fifty_pays_the_salary_past_the_start_and_resolves_the_tile() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 3));
            CloudRouteState s = apply(e, table(2).at(0, 14).build(), 0, ROLL).newState();
            assertThat(((Pending.AwaitFlight) s.pending()).candidates()).hasSize(23).doesNotContain(Board.STATION);
            Result r = apply(e, s, 0, new CloudRouteAction.Fly(2));
            assertThat(r.newState().cash(0)).isEqualTo(1_650);
            assertThat(r.newState().pending()).isEqualTo(new Pending.AwaitPurchase(2, 2));
            assertThat(r.newState().flewThisTurn()).isTrue();
            CloudRouteState fixed = s;
            assertThat(rejection(() -> e.apply(fixed, 0, new CloudRouteAction.Fly(18)))).isEqualTo(RejectionReason.INVALID_TILE);
        }

        @Test
        void only_one_flight_per_turn() {
            Result r = apply(engine(2, new ScriptedDice(1, 3)), table(2).at(0, 14).flew().build(), 0, ROLL);
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }
    }

    @Nested
    class Whirlpool {
        @Test
        void doubles_leave_and_move_without_an_extra_roll() {
            CloudRouteEngine e = engine(2, new ScriptedDice(2, 2));
            Result r = apply(e, table(2).trapped(0, 0).build(), 0, ROLL);
            assertThat(r.events()).contains(new CloudRouteEvent.WhirlpoolLeft(0, LeaveMethod.DOUBLES));
            CloudRouteState s = apply(e, r.newState(), 0, DECLINE).newState(); // 10 풍차 들판
            assertThat(s.turnSeat()).isEqualTo(1);
        }

        @Test
        void a_failed_roll_counts_and_the_third_turn_frees_by_itself() {
            CloudRouteState s = apply(engine(2, new ScriptedDice(1, 2)), table(2).trapped(0, 1).build(), 0, ROLL).newState();
            assertThat(s.seat(0).failedEscapes()).isEqualTo(2);
            assertThat(s.turnSeat()).isEqualTo(1);
            CloudRouteState next = table(2).trapped(0, 2).turn(1).cash(1, 50).build();
            Result r = apply(engine(2, new ScriptedDice(2, 3)), next, 1, ROLL);
            assertThat(r.events()).contains(new CloudRouteEvent.WhirlpoolLeft(0, LeaveMethod.SERVED),
                    new CloudRouteEvent.DecisionRequired(0, new Pending.AwaitRoll()));
        }

        @Test
        void paying_fifty_frees_and_then_rolls_normally() {
            CloudRouteEngine e = engine(2, new ScriptedDice(2, 2));
            CloudRouteState s = apply(e, table(2).trapped(0, 0).build(), 0, new CloudRouteAction.PayEscape()).newState();
            assertThat(s.cash(0)).isEqualTo(1_450);
            assertThat(s.pending()).isEqualTo(new Pending.AwaitRoll());
            s = apply(e, s, 0, ROLL).newState();
            s = apply(e, s, 0, DECLINE).newState();
            assertThat(s.pending()).isEqualTo(new Pending.AwaitRoll()); // 보통 굴림이라 더블이면 한 번 더
            assertThat(s.turnSeat()).isZero();
        }

        @Test
        void the_buoy_frees_once_and_goes_to_the_discard() {
            CloudRouteEngine e = engine(2, new ScriptedDice());
            assertThat(rejection(() -> e.apply(table(2).trapped(0, 0).build(), 0, new CloudRouteAction.UseBuoy())))
                    .isEqualTo(RejectionReason.NO_BUOY);
            CloudRouteState s = apply(e, table(2).buoy(0).trapped(0, 0).build(), 0, new CloudRouteAction.UseBuoy()).newState();
            assertThat(s.buoyHolder()).isEqualTo(-1);
            assertThat(s.letterDiscard()).containsExactly(Letter.BUOY);
            assertThat(s.seat(0).trapped()).isFalse();
        }
    }

    @Nested
    class Letters {
        private Result draw(int seats, CloudRouteTables.Builder b, Letter letter) {
            return apply(engine(seats, new ScriptedDice(1, 2)), b.deckTop(letter).build(), 0, ROLL);
        }

        @Test
        void tailwind_flies_to_the_start_with_salary() {
            Result r = draw(2, table(2), Letter.TAILWIND);
            assertThat(r.newState().seat(0).position()).isZero();
            assertThat(r.newState().cash(0)).isEqualTo(1_700);
        }

        @Test
        void sail_repair_and_star_chest_move_money_with_the_bank() {
            assertThat(draw(2, table(2), Letter.SAIL_REPAIR).newState().cash(0)).isEqualTo(1_420);
            assertThat(draw(2, table(2), Letter.STAR_CHEST).newState().cash(0)).isEqualTo(1_620);
        }

        @Test
        void headwind_goes_back_three_without_salary() {
            Result r = draw(2, table(2), Letter.HEADWIND);
            assertThat(r.newState().seat(0).position()).isZero();
            assertThat(r.newState().cash(0)).isEqualTo(1_500);
        }

        @Test
        void whirlpool_alert_traps_without_salary() {
            Result r = draw(2, table(2), Letter.WHIRLPOOL_ALERT);
            assertThat(r.newState().seat(0).trapped()).isTrue();
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }

        @Test
        void the_buoy_is_kept_out_of_both_piles() {
            CloudRouteState s = draw(2, table(2), Letter.BUOY).newState();
            assertThat(s.buoyHolder()).isZero();
            assertThat(s.letterDeck()).doesNotContain(Letter.BUOY);
            assertThat(s.letterDiscard()).isEmpty();
        }

        @Test
        void inspection_charges_twenty_per_level() {
            CloudRouteState s = draw(2, table(2).own(0, 1, 2).own(0, 2, 1), Letter.INSPECTION).newState();
            assertThat(s.cash(0)).isEqualTo(1_440);
        }

        @Test
        void island_feast_collects_thirty_from_each_other_player() {
            CloudRouteState s = draw(3, table(3), Letter.ISLAND_FEAST).newState();
            assertThat(s.seats()).extracting(x -> x.cash()).containsExactly(1_560, 1_470, 1_470);
        }

        @Test
        void wind_share_moves_a_hundred_from_the_richest_to_the_poorest() {
            Result r = draw(3, table(3).cash(1, 2_000).cash(2, 100), Letter.WIND_SHARE);
            assertThat(r.newState().seats()).extracting(x -> x.cash()).containsExactly(1_500, 1_900, 200);
        }

        @Test
        void gust_pushes_two_ahead_and_resolves_the_tile() {
            Result r = draw(2, table(2), Letter.GUST);
            assertThat(r.newState().pending()).isEqualTo(new Pending.AwaitPurchase(5, 2));
        }

        @Test
        void a_lantern_gift_builds_one_free_level_on_a_land_below_the_shop() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 2));
            CloudRouteState s = apply(e, table(2).own(0, 1, 2).own(0, 4, 1).own(0, 7, 0).deckTop(Letter.LANTERN_GIFT).build(),
                    0, ROLL).newState();
            assertThat(s.pending()).isEqualTo(new Pending.AwaitGift(List.of(4, 7))); // 상점(2)인 1번은 빠진다
            assertThat(e.timeoutAction(s, 0)).isEqualTo(new CloudRouteAction.GiftBuild(4)); // 시간 초과면 가장 싼 땅
            assertThat(e.legalActions(s, 0)).doesNotContain(new CloudRouteAction.Skip());
            Result r = apply(e, s, 0, new CloudRouteAction.GiftBuild(7));
            assertThat(of(r, CloudRouteEvent.Built.class)).containsExactly(new CloudRouteEvent.Built(0, 7, 1, 0, 1_500));
            CloudRouteState fixed = s;
            assertThat(rejection(() -> e.apply(fixed, 0, new CloudRouteAction.GiftBuild(1)))).isEqualTo(RejectionReason.INVALID_TILE);
        }

        @Test
        void a_lantern_gift_without_a_land_does_nothing() {
            Result r = draw(2, table(2), Letter.LANTERN_GIFT);
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }

        @Test
        void a_free_flight_ticket_moves_to_the_station_and_flies_without_paying() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 2));
            CloudRouteState s = apply(e, table(2).cash(0, 10).deckTop(Letter.FREE_FLIGHT).build(), 0, ROLL).newState();
            assertThat(s.seat(0).position()).isEqualTo(Board.STATION);
            assertThat(((Pending.AwaitFlight) s.pending()).free()).isTrue();
            Result r = apply(e, s, 0, new CloudRouteAction.Fly(1)); // 18 → 1, 출발을 지나 급여
            assertThat(of(r, CloudRouteEvent.MoneyChanged.class)).isEmpty();
            assertThat(r.newState().cash(0)).isEqualTo(210);
        }

        @Test
        void a_free_flight_ticket_after_a_flight_only_moves() {
            CloudRouteState s = table(2).flew().deckTop(Letter.FREE_FLIGHT).build();
            Result r = apply(engine(2, new ScriptedDice(1, 2)), s, 0, ROLL);
            assertThat(r.newState().seat(0).position()).isEqualTo(Board.STATION);
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }

        @Test
        void an_empty_deck_reshuffles_the_discard() {
            Result r = apply(engine(2, new ScriptedDice(1, 2)), table(2).emptyDeck().build(), 0, ROLL);
            CloudRouteEvent.LetterDrawn drawn = of(r, CloudRouteEvent.LetterDrawn.class).getFirst();
            assertThat(drawn.reshuffled()).isTrue();
            assertThat(drawn.deckCount()).isEqualTo(Letter.values().length - 1);
        }
    }

    @Nested
    class PaymentAndElimination {
        /** 남의 차례에 내는 돈(섬 잔치)은 자동 청산이다 — 싼 땅의 건물부터 한 단계씩, 다음 땅(§10). */
        @Test
        void an_off_turn_payment_liquidates_automatically_cheapest_buildings_first_then_lands() {
            CloudRouteState s = table(2).cash(1, 10).own(1, 1, 1).own(1, 4, 2).own(1, 7, 0).festival(1)
                    .deckTop(Letter.ISLAND_FEAST).build();
            Result r = apply(engine(2, new ScriptedDice(1, 2)), s, 0, ROLL);
            CloudRouteEvent.AssetsLiquidated sold = of(r, CloudRouteEvent.AssetsLiquidated.class).getFirst();
            assertThat(sold.seat()).isEqualTo(1);
            assertThat(sold.sales()).containsExactly(new Sale(1, false, 0, 15), new Sale(4, false, 1, 20));
            assertThat(r.newState().cash(1)).isEqualTo(15);
            assertThat(r.newState().owners().get(1)).isEqualTo(1);
        }

        /** D-217 — 내 차례에 낼 돈이 모자라면 팔 칸을 직접 고른다. 칸마다 다음 단위(건물 한 단계, 없으면 땅). */
        @Test
        void a_short_turn_payment_asks_which_tile_to_sell_one_unit_at_a_time() {
            CloudRouteEngine e = engine(2, new ScriptedDice(1, 3));
            CloudRouteState s = table(2).at(0, 5).cash(0, 10).own(0, 1, 1).own(0, 4, 2).own(0, 7, 0).festival(7).build();
            s = apply(e, s, 0, ROLL).newState(); // 세관 100G
            assertThat(s.pending()).isEqualTo(new Pending.AwaitSale(100, -1, "CUSTOMS", -1, false, false, List.of(1, 4, 7)));
            assertThat(e.timeoutAction(s, 0)).isEqualTo(new CloudRouteAction.Sell(1)); // 시간 초과 = 자동 순서
            Result r = apply(e, s, 0, new CloudRouteAction.Sell(7)); // 버섯 숲길 땅 +50 → 60, 축제 꺼짐
            assertThat(r.events()).contains(new CloudRouteEvent.FestivalCleared(7));
            s = r.newState();
            assertThat(s.pending()).isInstanceOf(Pending.AwaitSale.class);
            assertThat(((Pending.AwaitSale) s.pending()).candidates()).containsExactly(1, 4);
            r = apply(e, s, 0, new CloudRouteAction.Sell(4)); // 상점 → 천막 +20 → 80
            r = apply(e, r.newState(), 0, new CloudRouteAction.Sell(4)); // 천막 → 땅만 +20 → 100 → 냄
            assertThat(of(r, CloudRouteEvent.MoneyChanged.class))
                    .containsExactly(new CloudRouteEvent.MoneyChanged(0, -100, MoneyReason.CUSTOMS, 0));
            assertThat(r.newState().turnSeat()).isEqualTo(1);
            assertThat(r.newState().owners().get(1)).isZero();
            assertThat(r.newState().levels().get(1)).isEqualTo(1);
        }

        @Test
        void a_manual_sale_for_a_toll_pays_the_owner_and_still_offers_the_takeover() {
            CloudRouteEngine e = engine(2, new ScriptedDice(3, 4));
            CloudRouteState s = table(2).cash(0, 100).own(0, 23, 0).own(1, 7, 2).build();
            s = apply(e, s, 0, ROLL).newState(); // 버섯 숲길 상점 140G
            assertThat(s.pending()).isEqualTo(new Pending.AwaitSale(140, 1, "TOLL", 7, false, false, List.of(23)));
            Result r = apply(e, s, 0, new CloudRouteAction.Sell(23)); // +120 → 220 → 140 냄 → 80
            assertThat(of(r, CloudRouteEvent.TollPaid.class))
                    .containsExactly(new CloudRouteEvent.TollPaid(0, 1, 7, 140, false, false, 80, 1_640));
            assertThat(r.newState().turnSeat()).isEqualTo(1); // 인수가 300 > 80 이라 인수 제안 없음
        }

        @Test
        void selling_everything_short_is_bankruptcy() {
            CloudRouteState s = table(3).cash(0, 0).own(0, 1, 0).own(1, 7, 3).build();
            CloudRouteEngine e3 = engine(3, new ScriptedDice(3, 4));
            s = apply(e3, s, 0, ROLL).newState(); // 280G
            Result r = apply(e3, s, 0, new CloudRouteAction.Sell(1)); // +30 → 30 전부 내고 파산
            assertThat(of(r, CloudRouteEvent.TollPaid.class).getFirst().amount()).isEqualTo(30);
            assertThat(r.newState().alive(0)).isFalse();
            assertThat(r.newState().turnSeat()).isEqualTo(1);
        }

        @Test
        void a_debt_vanishes_when_its_creditor_deserts_during_the_sale() {
            CloudRouteEngine e = engine(3, new ScriptedDice(3, 4));
            CloudRouteState s = apply(e, table(3).cash(0, 100).own(0, 23, 0).own(1, 7, 2).build(), 0, ROLL).newState();
            Desertion d = e.desert(s, 1);
            assertThat(d.newState().turnSeat()).isEqualTo(2);
            assertThat(d.newState().cash(0)).isEqualTo(100);
            CloudRouteInvariantChecker.check(d.newState());
        }

        @Test
        void bankruptcy_pays_everything_to_the_owner_and_returns_the_lands_to_the_bank() {
            CloudRouteState s = table(3).cash(0, 20).own(1, 7, 3).buoy(0).build();
            Result r = apply(engine(3, new ScriptedDice(3, 4)), s, 0, ROLL);
            assertThat(of(r, CloudRouteEvent.TollPaid.class).getFirst().amount()).isEqualTo(20);
            assertThat(of(r, CloudRouteEvent.PlayerEliminated.class)).containsExactly(
                    new CloudRouteEvent.PlayerEliminated(0, Elimination.Reason.BANKRUPT, 1, List.of(), 0));
            CloudRouteState after = r.newState();
            assertThat(after.cash(1)).isEqualTo(1_520);
            assertThat(after.buoyHolder()).isEqualTo(-1);
            assertThat(after.turnSeat()).isEqualTo(1);
            assertThat(after.ended()).isFalse();
        }

        @Test
        void a_feast_that_bankrupts_the_only_rival_ends_as_last_standing() {
            CloudRouteState s = table(2).cash(1, 10).deckTop(Letter.ISLAND_FEAST).build();
            CloudRouteState end = apply(engine(2, new ScriptedDice(1, 2)), s, 0, ROLL).newState();
            assertThat(end.result().reason()).isEqualTo(EndReason.LAST_STANDING);
            assertThat(end.result().winners()).containsExactly(0);
            assertThat(end.cash(0)).isEqualTo(1_510);
        }

        @Test
        void a_deserting_turn_seat_passes_the_turn_and_frees_its_lands() {
            CloudRouteState s = table(3).own(0, 4, 2).pending(new Pending.AwaitRoll()).build();
            Desertion d = new CloudRouteEngine(CloudRouteTables.context(3), new Random(1)).desert(s, 0);
            assertThat(d.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
            assertThat(d.newState().turnSeat()).isEqualTo(1);
            assertThat(d.newState().owners().get(4)).isEqualTo(-1);
            assertThat(d.newState().levels().get(4)).isZero();
            CloudRouteInvariantChecker.check(d.newState());
        }

        @Test
        void a_takeover_offer_vanishes_when_the_seller_deserts() {
            CloudRouteState s = table(3).at(0, 7).own(1, 7, 1).pending(new Pending.AwaitTakeover(7, 1, 230)).build();
            Desertion d = new CloudRouteEngine(CloudRouteTables.context(3), new Random(1)).desert(s, 1);
            assertThat(d.newState().turnSeat()).isEqualTo(2);
            assertThat(d.newState().owners().get(7)).isEqualTo(-1);
            CloudRouteInvariantChecker.check(d.newState());
        }

        @Test
        void when_only_bots_remain_the_match_ends_as_no_humans() {
            CloudRouteState s = table(3).build();
            Desertion d = new CloudRouteEngine(CloudRouteTables.context(3, 1, 2), new Random(1)).desert(s, 0);
            assertThat(d.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
            assertThat(d.newState().result().reason()).isEqualTo(EndReason.NO_HUMANS);
        }

        @Test
        void an_eliminated_seat_cannot_desert_or_act_again() {
            CloudRouteState s = table(3).turn(1).eliminated(0, Elimination.Reason.BANKRUPT).build();
            CloudRouteEngine e = new CloudRouteEngine(CloudRouteTables.context(3), new Random(1));
            assertThat(e.desert(s, 0).outcome()).isEqualTo(Desertion.Outcome.NOT_APPLICABLE);
            assertThat(rejection(() -> e.apply(s, 0, ROLL))).isEqualTo(RejectionReason.PLAYER_ELIMINATED);
        }
    }

    @Nested
    class Ranking {
        private CloudRouteState lastTurnOf4(Elimination.Reason first, Elimination.Reason second) {
            CloudRouteState s = table(4).turn(1).round(20).at(1, 19).cash(0, 1_700)
                    .eliminated(3, first).eliminated(2, second).build();
            return apply(engine(4, new ScriptedDice(2, 3)), s, 1, ROLL).newState();
        }

        @Test
        void equal_assets_share_a_rank_and_a_later_bankruptcy_ranks_higher() {
            List<Standing> standings = lastTurnOf4(Elimination.Reason.BANKRUPT, Elimination.Reason.BANKRUPT)
                    .result().standings();
            assertThat(standings).extracting(Standing::seat, Standing::rank)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 1), org.assertj.core.groups.Tuple.tuple(1, 1),
                            org.assertj.core.groups.Tuple.tuple(2, 3), org.assertj.core.groups.Tuple.tuple(3, 4));
        }

        @Test
        void deserters_rank_below_every_bankruptcy() {
            List<Standing> standings = lastTurnOf4(Elimination.Reason.BANKRUPT, Elimination.Reason.DESERTED)
                    .result().standings();
            assertThat(standings).extracting(Standing::seat, Standing::status).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(0, SeatStatus.ALIVE), org.assertj.core.groups.Tuple.tuple(1, SeatStatus.ALIVE),
                    org.assertj.core.groups.Tuple.tuple(3, SeatStatus.BANKRUPT), org.assertj.core.groups.Tuple.tuple(2, SeatStatus.DESERTED));
        }
    }

    @Nested
    class Rejections {
        @Test
        void out_of_turn_wrong_decision_and_finished_match_are_refused() {
            CloudRouteEngine e = engine(2, new ScriptedDice());
            CloudRouteState s = table(2).build();
            assertThat(rejection(() -> e.apply(s, 1, ROLL))).isEqualTo(RejectionReason.NOT_YOUR_TURN);
            assertThat(rejection(() -> e.apply(s, 0, new CloudRouteAction.Buy(0)))).isEqualTo(RejectionReason.WRONG_DECISION);
            assertThat(rejection(() -> e.apply(s, 0, SKIP))).isEqualTo(RejectionReason.WRONG_DECISION);
            CloudRouteState end = new CloudRouteEngine(CloudRouteTables.context(2), new Random(1)).desert(s, 1).newState();
            assertThat(rejection(() -> e.apply(end, 0, ROLL))).isEqualTo(RejectionReason.MATCH_OVER);
        }

        @Test
        void legal_actions_list_every_choice_and_the_timeout_does_nothing() {
            CloudRouteEngine e = engine(2, new ScriptedDice());
            CloudRouteState s = table(2).at(0, 4).pending(new Pending.AwaitPurchase(4, 2)).build();
            assertThat(e.legalActions(s, 0)).containsExactly(new CloudRouteAction.Buy(0), new CloudRouteAction.Buy(1),
                    new CloudRouteAction.Buy(2), DECLINE);
            assertThat(e.legalActions(s, 1)).isEmpty();
            assertThat(e.timeoutAction(s, 0)).isEqualTo(DECLINE);
            assertThat(e.pendingSeats(s)).containsExactly(0);
        }
    }
}
