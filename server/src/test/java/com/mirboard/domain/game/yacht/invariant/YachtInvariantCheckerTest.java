package com.mirboard.domain.game.yacht.invariant;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static com.mirboard.domain.game.yacht.YachtFixtures.allBut;
import static com.mirboard.domain.game.yacht.YachtFixtures.openState;
import static com.mirboard.domain.game.yacht.YachtFixtures.withCard;
import static com.mirboard.domain.game.yacht.YachtFixtures.withDeserted;
import static com.mirboard.domain.game.yacht.YachtFixtures.withTurn;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.rules.Ranking;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.TurnState;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 통과 1건 + 고의 위반마다 해당 규칙만 걸리는지. */
class YachtInvariantCheckerTest {

    private static List<String> violations(YachtState s, Integer... bots) {
        return YachtInvariantChecker.violations(s, Set.of(bots));
    }

    private static YachtState ended(YachtState open, EndReason reason) {
        YachtState base = new YachtState(open.cards(), open.round(), -1, TurnState.none(), open.deserted(),
                open.actionCount(), null);
        return new YachtState(base.cards(), base.round(), -1, base.turn(), base.deserted(), base.actionCount(),
                Ranking.result(base, reason));
    }

    @Test
    void a_consistent_open_state_passes() {
        assertThat(violations(withTurn(openState(3, 5, 1), Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 2))).isEmpty();
    }

    @Test
    void dice_without_rolls_or_rolls_without_dice() {
        YachtState s = openState(2, 1, 0);
        assertThat(violations(withTurn(s, Dice.of(1, 1, 1, 1, 1), HoldMask.NONE, 0))).hasSize(1);
        assertThat(violations(withTurn(s, null, HoldMask.NONE, 2))).hasSize(1);
    }

    @Test
    void held_before_the_first_roll() {
        assertThat(violations(withTurn(openState(2, 1, 0), null, HoldMask.ALL, 0)))
                .singleElement().asString().contains("held before first roll");
    }

    @Test
    void rolls_out_of_range() {
        YachtState s = openState(2, 1, 0);
        YachtState bad = new YachtState(s.cards(), 1, 0, new TurnState(Dice.of(1, 1, 1, 1, 1), HoldMask.NONE, 4, T0),
                List.of(), 0, null);
        assertThat(violations(bad)).singleElement().asString().contains("rolls out of range");
    }

    @Test
    void a_seat_with_the_wrong_number_of_filled_categories() {
        YachtState s = withCard(openState(3, 4, 1), 2, Scorecard.EMPTY);
        assertThat(violations(s)).singleElement().asString().contains("seat 2 filled 0 expected 3");
    }

    @Test
    void a_score_above_the_category_maximum() {
        YachtState s = withCard(openState(1, 2, 0), 0, Scorecard.EMPTY.with(Category.ONES, 6));
        assertThat(violations(s)).singleElement().asString().contains("above max");
    }

    @Test
    void the_turn_on_a_deserted_seat() {
        assertThat(violations(withDeserted(openState(3, 2, 1), 1)))
                .singleElement().asString().contains("turn seat not alive");
    }

    @Test
    void an_open_match_with_only_bots() {
        assertThat(violations(openState(2, 2, 0), 0, 1)).singleElement().asString().contains("without a living human");
    }

    @Test
    void an_open_match_past_the_action_limit() {
        YachtState s = openState(1, 1, 0);
        YachtState over = new YachtState(s.cards(), 1, 0, s.turn(), List.of(), s.actionLimit(), null);
        assertThat(violations(over)).singleElement().asString().contains("action limit");
    }

    @Test
    void finished_requires_every_living_seat_full() {
        YachtState full = withCard(withCard(openState(2, 12, 0), 0, allBut(null)), 1, allBut(null));
        assertThat(violations(ended(full, EndReason.FINISHED))).isEmpty();

        YachtState notFull = withCard(full, 1, allBut(Category.YACHT));
        assertThat(violations(ended(notFull, EndReason.FINISHED)))
                .singleElement().asString().contains("FINISHED without");
    }

    @Test
    void no_humans_requires_no_living_human() {
        YachtState s = withDeserted(openState(2, 3, 0), 0);
        assertThat(violations(ended(s, EndReason.NO_HUMANS), 1)).isEmpty();
        assertThat(violations(ended(s, EndReason.NO_HUMANS))).singleElement().asString().contains("NO_HUMANS");
    }

    @Test
    void action_limit_requires_the_limit() {
        assertThat(violations(ended(openState(2, 3, 0), EndReason.ACTION_LIMIT)))
                .singleElement().asString().contains("ACTION_LIMIT");
    }

    @Test
    void placements_must_match_the_ranking() {
        YachtState full = withCard(withCard(openState(2, 12, 0), 0, allBut(null)), 1, allBut(null));
        YachtState good = ended(full, EndReason.FINISHED);
        YachtState bad = new YachtState(good.cards(), 12, -1, good.turn(), List.of(), 0,
                new MatchResult(EndReason.FINISHED, List.of(new MatchResult.Placement(1, 1, 0, false))));
        assertThat(violations(bad)).singleElement().asString().contains("placements differ");
    }

    @Test
    void an_ended_match_with_a_turn_seat_or_dice() {
        YachtState full = withCard(withCard(openState(2, 12, 0), 0, allBut(null)), 1, allBut(null));
        YachtState good = ended(full, EndReason.FINISHED);
        YachtState withSeat = new YachtState(good.cards(), 12, 0, good.turn(), List.of(), 0, good.result());
        assertThat(violations(withSeat)).singleElement().asString().contains("turn seat");
        YachtState withDice = new YachtState(good.cards(), 12, -1,
                new TurnState(Dice.of(1, 1, 1, 1, 1), HoldMask.NONE, 1, T0), List.of(), 0, good.result());
        assertThat(violations(withDice)).singleElement().asString().contains("keeps dice");
    }
}
