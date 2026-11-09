package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static com.mirboard.domain.game.yacht.YachtFixtures.allBut;
import static com.mirboard.domain.game.yacht.YachtFixtures.engine;
import static com.mirboard.domain.game.yacht.YachtFixtures.timedEngine;
import static com.mirboard.domain.game.yacht.YachtFixtures.openState;
import static com.mirboard.domain.game.yacht.YachtFixtures.withCard;
import static com.mirboard.domain.game.yacht.YachtFixtures.withTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.yacht.action.RejectionReason;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.action.YachtActionRejectedException;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.YachtState;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** `docs/rules-yacht.md` §3·§4 — 한 차례와 거절 사유. */
class YachtEngineTurnTest {

    private static final DiceRoller NEVER = () -> {
        throw new AssertionError("must not roll");
    };

    private final YachtEngine engine = engine(2);

    private static void rejected(ThrowingCallable call, RejectionReason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YachtActionRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static HoldMask mask(String bits) {
        return HoldMask.of(bits.charAt(0) == '1', bits.charAt(1) == '1', bits.charAt(2) == '1',
                bits.charAt(3) == '1', bits.charAt(4) == '1');
    }

    @Test
    void start_is_round_1_seat_0_with_empty_dice() {
        YachtEngine.Result r = timedEngine(3, 45).start(T0);
        assertThat(r.newState().round()).isEqualTo(1);
        assertThat(r.newState().turnSeat()).isZero();
        assertThat(r.newState().turn().rolls()).isZero();
        assertThat(r.newState().turn().startedAt()).isEqualTo(T0);
        assertThat(r.events()).containsExactly(new YachtEvent.TurnChanged(0, 1, 45));
    }

    @Test
    void first_roll_rolls_all_five_and_announces_candidates() {
        YachtState s = engine.start(T0).newState();
        YachtEngine.Result r = engine.apply(s, 0, YachtAction.Roll.plain(), DiceRoller.sequence(3, 3, 3, 2, 2), T0);

        assertThat(r.newState().turn().dice()).isEqualTo(Dice.of(3, 3, 3, 2, 2));
        assertThat(r.newState().turn().rolls()).isEqualTo(1);
        assertThat(r.events()).hasSize(1);
        YachtEvent.DiceRolled rolled = (YachtEvent.DiceRolled) r.events().get(0);
        assertThat(rolled.rolls()).isEqualTo(1);
        assertThat(rolled.candidates()).hasSize(12).containsEntry(Category.FULL_HOUSE, 13)
                .containsEntry(Category.THREES, 9);
        assertThat(r.newState().actionCount()).isEqualTo(1);
    }

    @Test
    void reroll_only_changes_unheld_dice() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(6, 6, 1, 2, 6), mask("11001"), 1);
        YachtEngine.Result r = engine.apply(s, 0, YachtAction.Roll.plain(), DiceRoller.sequence(6, 5), T0);
        assertThat(r.newState().turn().dice()).isEqualTo(Dice.of(6, 6, 6, 5, 6));
        assertThat(r.newState().turn().rolls()).isEqualTo(2);
    }

    @Test
    void roll_with_held_applies_the_hold_first_and_announces_it() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(6, 6, 1, 2, 6), HoldMask.NONE, 1);
        YachtEngine.Result r = engine.apply(s, 0, new YachtAction.Roll(mask("11001")), DiceRoller.sequence(4, 4), T0);

        assertThat(r.events().get(0)).isEqualTo(new YachtEvent.DiceHeld(0, mask("11001")));
        assertThat(r.events().get(1)).isInstanceOf(YachtEvent.DiceRolled.class);
        assertThat(r.newState().turn().dice()).isEqualTo(Dice.of(6, 6, 4, 4, 6));
        assertThat(r.newState().turn().held()).isEqualTo(mask("11001"));
    }

    @Test
    void roll_with_the_same_held_announces_no_hold() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(6, 6, 1, 2, 6), mask("11001"), 1);
        YachtEngine.Result r = engine.apply(s, 0, new YachtAction.Roll(mask("11001")), DiceRoller.sequence(4, 4), T0);
        assertThat(r.events()).hasSize(1).first().isInstanceOf(YachtEvent.DiceRolled.class);
    }

    @Test
    void hold_changes_the_mask() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(1, 2, 3, 4, 6), HoldMask.NONE, 2);
        YachtEngine.Result r = engine.apply(s, 0, new YachtAction.Hold(mask("11110")), NEVER, T0);
        assertThat(r.newState().turn().held()).isEqualTo(mask("11110"));
        assertThat(r.events()).containsExactly(new YachtEvent.DiceHeld(0, mask("11110")));
    }

    @Test
    void holding_all_five_is_allowed_but_rolling_them_is_not() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(5, 5, 5, 5, 5), HoldMask.NONE, 1);
        YachtState allHeld = engine.apply(s, 0, new YachtAction.Hold(HoldMask.ALL), NEVER, T0).newState();
        rejected(() -> engine.apply(allHeld, 0, YachtAction.Roll.plain(), NEVER, T0), RejectionReason.NOTHING_TO_ROLL);
        rejected(() -> engine.apply(s, 0, new YachtAction.Roll(HoldMask.ALL), NEVER, T0), RejectionReason.NOTHING_TO_ROLL);
    }

    @Test
    void score_writes_the_points_and_passes_the_turn() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(2, 2, 3, 3, 3), HoldMask.NONE, 1);
        YachtEngine.Result r = engine.apply(s, 0, new YachtAction.Score(Category.FULL_HOUSE), NEVER, T0 + 5_000);

        assertThat(r.newState().card(0).scores()).containsEntry(Category.FULL_HOUSE, 13);
        assertThat(r.newState().turnSeat()).isEqualTo(1);
        assertThat(r.newState().round()).isEqualTo(1);
        assertThat(r.newState().turn().rolls()).isZero();
        assertThat(r.newState().turn().startedAt()).isEqualTo(T0 + 5_000);
        assertThat(r.events()).containsExactly(
                new YachtEvent.Scored(0, Category.FULL_HOUSE, 13, 0, 0, 13, false),
                new YachtEvent.TurnChanged(1, 1, 0));
    }

    @Test
    void scoring_zero_in_an_unmatched_category_is_allowed() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(1, 2, 3, 5, 6), HoldMask.NONE, 3);
        YachtEngine.Result r = engine.apply(s, 0, new YachtAction.Score(Category.YACHT), NEVER, T0);
        assertThat(r.newState().card(0).scores()).containsEntry(Category.YACHT, 0);
    }

    @Test
    void the_last_seat_of_a_round_starts_the_next_round_from_the_first_seat() {
        YachtState s = withTurn(openState(2, 3, 1), Dice.of(1, 1, 1, 1, 1), HoldMask.NONE, 1);
        YachtEngine.Result r = engine.apply(s, 1, new YachtAction.Score(Category.YACHT), NEVER, T0);
        assertThat(r.newState().round()).isEqualTo(4);
        assertThat(r.newState().turnSeat()).isZero();
        assertThat(r.events()).last().isEqualTo(new YachtEvent.TurnChanged(0, 4, 0));
    }

    @Test
    void the_last_score_of_round_12_finishes_the_match() {
        YachtState s = withTurn(openState(2, 12, 1), Dice.of(6, 6, 6, 6, 6), HoldMask.NONE, 1);
        s = withCard(s, 0, allBut(null)); // 좌석 0 은 다 채움
        s = withCard(s, 1, allBut(Category.YACHT));
        YachtEngine.Result r = engine.apply(s, 1, new YachtAction.Score(Category.YACHT), NEVER, T0);

        assertThat(r.newState().ended()).isTrue();
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(r.newState().turnSeat()).isEqualTo(-1);
        assertThat(r.newState().result().winners()).containsExactly(1);
        assertThat(r.events()).last().isInstanceOf(YachtEvent.MatchEnded.class);
        assertThat(r.events()).noneMatch(e -> e instanceof YachtEvent.TurnChanged);
    }

    @Test
    void a_solo_match_cycles_through_12_rounds_of_its_only_seat() {
        YachtEngine solo = engine(1);
        YachtState s = solo.start(T0).newState();
        for (int round = 1; round <= 12; round++) {
            assertThat(s.round()).isEqualTo(round);
            s = solo.apply(s, 0, YachtAction.Roll.plain(), DiceRoller.sequence(1, 2, 3, 4, 5), T0).newState();
            s = solo.apply(s, 0, new YachtAction.Score(com.mirboard.domain.game.yacht.score.Scoring
                    .best(s.card(0), s.turn().dice())), NEVER, T0).newState();
        }
        assertThat(s.ended()).isTrue();
        assertThat(s.result().reason()).isEqualTo(EndReason.FINISHED);
    }

    // ---- 거절 (§4.1) ----

    @Test
    void rejections() {
        YachtState fresh = engine.start(T0).newState();
        YachtState rolled = withTurn(fresh, Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 1);
        YachtState third = withTurn(fresh, Dice.of(1, 2, 3, 4, 5), mask("10000"), 3);

        rejected(() -> engine.apply(fresh, 1, YachtAction.Roll.plain(), NEVER, T0), RejectionReason.NOT_YOUR_TURN);
        rejected(() -> engine.apply(third, 0, YachtAction.Roll.plain(), NEVER, T0), RejectionReason.NO_ROLLS_LEFT);
        rejected(() -> engine.apply(fresh, 0, new YachtAction.Roll(mask("10000")), NEVER, T0),
                RejectionReason.HOLD_NOT_ALLOWED);
        rejected(() -> engine.apply(fresh, 0, new YachtAction.Hold(mask("10000")), NEVER, T0),
                RejectionReason.HOLD_NOT_ALLOWED);
        rejected(() -> engine.apply(third, 0, new YachtAction.Hold(mask("11000")), NEVER, T0),
                RejectionReason.HOLD_NOT_ALLOWED);
        rejected(() -> engine.apply(rolled, 0, new YachtAction.Hold(HoldMask.NONE), NEVER, T0),
                RejectionReason.HOLD_UNCHANGED);
        rejected(() -> engine.apply(fresh, 0, new YachtAction.Score(Category.CHOICE), NEVER, T0),
                RejectionReason.NOT_ROLLED_YET);
        YachtState choiceUsed = withCard(rolled, 0, com.mirboard.domain.game.yacht.score.Scorecard.EMPTY
                .with(Category.CHOICE, 10));
        rejected(() -> engine.apply(choiceUsed, 0, new YachtAction.Score(Category.CHOICE), NEVER, T0),
                RejectionReason.CATEGORY_FILLED);
        rejected(() -> engine.apply(rolled, 0, new YachtAction.Score(null), NEVER, T0), RejectionReason.INVALID_ACTION);
        rejected(() -> engine.apply(rolled, 0, new YachtAction.Hold(null), NEVER, T0), RejectionReason.INVALID_ACTION);

        YachtState deserted = YachtFixtures.withDeserted(fresh, 0);
        rejected(() -> engine.apply(deserted, 0, YachtAction.Roll.plain(), NEVER, T0), RejectionReason.NOT_YOUR_TURN);
    }

    @Test
    void a_finished_match_rejects_everything() {
        YachtState s = withTurn(openState(2, 12, 1), Dice.of(6, 6, 6, 6, 6), HoldMask.NONE, 1);
        s = withCard(withCard(s, 0, allBut(null)), 1, allBut(Category.YACHT));
        YachtState ended = engine.apply(s, 1, new YachtAction.Score(Category.YACHT), NEVER, T0).newState();
        rejected(() -> engine.apply(ended, 0, YachtAction.Roll.plain(), NEVER, T0), RejectionReason.MATCH_OVER);
    }

    @Test
    void a_rejection_does_not_change_the_state() {
        YachtState rolled = withTurn(engine.start(T0).newState(), Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 1);
        YachtState copy = new YachtState(rolled.cards(), rolled.round(), rolled.turnSeat(), rolled.turn(),
                rolled.deserted(), rolled.actionCount(), rolled.result());
        rejected(() -> engine.apply(rolled, 0, new YachtAction.Hold(HoldMask.NONE), NEVER, T0),
                RejectionReason.HOLD_UNCHANGED);
        assertThat(rolled).isEqualTo(copy);
    }

    // ---- 액션 상한 (§8.1) ----

    @Test
    void reaching_the_action_limit_ends_the_match() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 1);
        YachtState nearLimit = new YachtState(s.cards(), s.round(), s.turnSeat(), s.turn(), s.deserted(),
                s.actionLimit() - 1, null);
        YachtEngine.Result r = engine.apply(nearLimit, 0, new YachtAction.Hold(mask("10000")), NEVER, T0);

        assertThat(r.newState().ended()).isTrue();
        assertThat(r.newState().result().reason()).isEqualTo(EndReason.ACTION_LIMIT);
        assertThat(r.events()).last().isInstanceOf(YachtEvent.MatchEnded.class);
    }

    // ---- 합법 액션 ----

    @Test
    void legal_actions_follow_the_turn_stage() {
        YachtState fresh = engine.start(T0).newState();
        assertThat(engine.legalActions(fresh, 0)).containsExactly(YachtAction.Roll.plain());
        assertThat(engine.legalActions(fresh, 1)).isEmpty();

        YachtState rolled = withTurn(fresh, Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 1);
        assertThat(engine.legalActions(rolled, 0)).hasSize(31 + 1 + 12);

        YachtState allHeld = withTurn(fresh, Dice.of(1, 2, 3, 4, 5), HoldMask.ALL, 2);
        assertThat(engine.legalActions(allHeld, 0)).hasSize(31 + 12)
                .doesNotContain(YachtAction.Roll.plain());

        YachtState third = withTurn(fresh, Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 3);
        assertThat(engine.legalActions(third, 0)).hasSize(12)
                .allMatch(a -> a instanceof YachtAction.Score);
    }

    @Test
    void every_legal_action_is_accepted() {
        YachtState rolled = withTurn(engine.start(T0).newState(), Dice.of(1, 2, 3, 4, 5), mask("00100"), 2);
        for (YachtAction a : engine.legalActions(rolled, 0)) {
            engine.apply(rolled, 0, a, DiceRoller.sequence(1, 1, 1, 1, 1), T0);
        }
    }
}
