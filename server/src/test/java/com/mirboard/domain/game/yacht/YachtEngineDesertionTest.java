package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static com.mirboard.domain.game.yacht.YachtFixtures.allBut;
import static com.mirboard.domain.game.yacht.YachtFixtures.engine;
import static com.mirboard.domain.game.yacht.YachtFixtures.openState;
import static com.mirboard.domain.game.yacht.YachtFixtures.withCard;
import static com.mirboard.domain.game.yacht.YachtFixtures.withTurn;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.yacht.YachtEngine.Desertion.Outcome;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.YachtState;
import org.junit.jupiter.api.Test;

/** `docs/rules-yacht.md` §7 — 탈주. */
class YachtEngineDesertionTest {

    @Test
    void deserting_on_your_turn_passes_it_without_scoring() {
        YachtEngine engine = engine(3);
        YachtState s = withTurn(openState(3, 2, 1), Dice.of(6, 6, 6, 6, 6), HoldMask.ALL, 2);
        YachtEngine.Desertion d = engine.desert(s, 1, T0 + 7);

        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_CONTINUES);
        assertThat(d.newState().turnSeat()).isEqualTo(2);
        assertThat(d.newState().round()).isEqualTo(2);
        assertThat(d.newState().card(1).filledCount()).isEqualTo(1);
        assertThat(d.newState().turn().rolls()).isZero();
        assertThat(d.newState().turn().startedAt()).isEqualTo(T0 + 7);
        assertThat(d.events()).containsExactly(new YachtEvent.PlayerEliminated(1, 0),
                new YachtEvent.TurnChanged(2, 2, 0));
    }

    @Test
    void deserting_off_turn_leaves_the_turn_alone_and_is_skipped_later() {
        YachtEngine engine = engine(3);
        YachtState s = openState(3, 2, 0);
        YachtEngine.Desertion d = engine.desert(s, 1, T0);
        assertThat(d.newState().turnSeat()).isZero();
        assertThat(d.events()).containsExactly(new YachtEvent.PlayerEliminated(1, 0));

        YachtState next = withTurn(d.newState(), Dice.of(1, 1, 1, 1, 1), HoldMask.NONE, 1);
        YachtState after = engine.apply(next, 0, new com.mirboard.domain.game.yacht.action.YachtAction.Score(
                Category.YACHT), () -> 1, T0).newState();
        assertThat(after.turnSeat()).isEqualTo(2);
    }

    @Test
    void the_last_seat_of_a_round_deserting_starts_the_next_round() {
        YachtEngine engine = engine(3);
        YachtEngine.Desertion d = engine.desert(openState(3, 4, 2), 2, T0);
        assertThat(d.newState().round()).isEqualTo(5);
        assertThat(d.newState().turnSeat()).isZero();
    }

    @Test
    void deserting_as_the_last_to_play_in_round_12_finishes_the_match() {
        YachtEngine engine = engine(2);
        YachtState s = withCard(withCard(openState(2, 12, 1), 0, allBut(null)), 1, allBut(Category.YACHT));
        YachtEngine.Desertion d = engine.desert(s, 1, T0);

        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        assertThat(d.newState().result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(d.newState().result().placements()).extracting(p -> p.seat() + ":" + p.rank() + ":" + p.deserted())
                .containsExactly("0:1:false", "1:2:true");
    }

    @Test
    void the_last_human_leaving_ends_the_match_for_the_bots() {
        YachtEngine engine = engine(3, 1, 2);
        YachtEngine.Desertion d = engine.desert(openState(3, 3, 1), 0, T0);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        assertThat(d.newState().result().reason()).isEqualTo(EndReason.NO_HUMANS);
        assertThat(d.events()).last().isInstanceOf(YachtEvent.MatchEnded.class);
    }

    @Test
    void a_solo_player_leaving_ends_the_match() {
        YachtEngine engine = engine(1);
        YachtEngine.Desertion d = engine.desert(engine.start(T0).newState(), 0, T0);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        assertThat(d.newState().result().reason()).isEqualTo(EndReason.NO_HUMANS);
        assertThat(d.newState().result().winners()).isEmpty();
    }

    @Test
    void one_human_left_keeps_playing() {
        YachtEngine engine = engine(2);
        YachtEngine.Desertion d = engine.desert(openState(2, 5, 0), 0, T0);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_CONTINUES);
        assertThat(d.newState().turnSeat()).isEqualTo(1);
    }

    @Test
    void deserting_twice_or_after_the_end_is_not_applicable() {
        YachtEngine engine = engine(3);
        YachtState once = engine.desert(openState(3, 2, 0), 1, T0).newState();
        assertThat(engine.desert(once, 1, T0).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(engine.desert(once, 1, T0).newState()).isSameAs(once);

        YachtEngine solo = engine(1);
        YachtState ended = solo.desert(solo.start(T0).newState(), 0, T0).newState();
        assertThat(solo.desert(ended, 0, T0).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }
}
