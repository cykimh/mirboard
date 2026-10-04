package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.CallOneCard;
import com.mirboard.domain.game.onecard.action.OneCardAction.Catch;
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.action.OneCardActionRejectedException;
import com.mirboard.domain.game.onecard.action.RejectionReason;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardsDrawn;
import com.mirboard.domain.game.onecard.event.OneCardEvent.DrawReason;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOpened;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardEvent.TurnChanged;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §9 — 외치기 경쟁 창. */
class OneCardEngineRaceTest {

    private static final long NOW = 50_000L;

    /** 봇 반응 시간을 고정한 설정 — 주인 봇 1.2초, 잡는 봇 1.5초. */
    private static final RaceSettings FIXED = new RaceSettings(3_000, 1_200, 1_200, 1_500, 1_500, 8);

    private static OneCardEngine engine(int seats, RaceSettings settings, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        return new OneCardEngine(new GameContext("r", ids, 0, 0, Arrays.asList(botSeats)), new Random(11), settings);
    }

    private static OneCardEngine humans(int seats) {
        return engine(seats, FIXED);
    }

    /** 좌석 0 이 두 장 중 한 장을 내 1장이 되는 테이블(3인). */
    private static OneCardState aboutToGoDownToOne(PlayingCard toPlay) {
        return seats(hand(toPlay, club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
    }

    private static RejectionReason rejection(OneCardEngine engine, OneCardState state, int seat, OneCardAction action) {
        try {
            engine.apply(state, seat, action, NOW);
        } catch (OneCardActionRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("expected a rejection");
    }

    @Test
    void going_down_to_one_card_opens_a_race_and_pauses_the_turn() {
        OneCardEngine engine = humans(3);

        OneCardEngine.Result result = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW);

        OneCardState state = result.newState();
        RaceWindow race = state.race();
        assertThat(race.ownerSeat()).isZero();
        assertThat(race.nextSeat()).isEqualTo(1);
        assertThat(race.openedAt()).isEqualTo(NOW);
        assertThat(race.slot()).isBetween(0, 7);
        assertThat(race.jitterX()).isBetween(-100, 100);
        assertThat(state.turnSeat()).isEqualTo(-1);
        assertThat(engine.pendingSeats(state)).isEmpty();
        assertThat(result.events()).noneMatch(TurnChanged.class::isInstance);
        assertThat(result.events().getLast()).isEqualTo(new RaceOpened(race.raceId(), 0, race.slot(),
                race.jitterX(), race.jitterY(), 3_000));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void the_owner_calling_first_is_safe_and_the_turn_moves_on() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        OneCardEngine.Result result = engine.apply(raced, 0, new CallOneCard(raceId), NOW + 900);

        assertThat(result.newState().race()).isNull();
        assertThat(result.newState().hands().get(0)).hasSize(1);
        assertThat(result.events()).containsExactly(
                new RaceResolved(raceId, RaceOutcome.CALLED, 0),
                new TurnChanged(1, 1, 0));
    }

    @Test
    void being_caught_costs_one_card_without_ending_the_turn_or_counting_as_one() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        OneCardEngine.Result result = engine.apply(raced, 2, new Catch(raceId), NOW + 700);

        OneCardState state = result.newState();
        assertThat(state.hands().get(0)).hasSize(2);
        assertThat(state.turnCount()).isEqualTo(raced.turnCount());
        assertThat(state.passStreak()).isEqualTo(raced.passStreak());
        assertThat(result.events().getFirst()).isEqualTo(new RaceResolved(raceId, RaceOutcome.CAUGHT, 2));
        assertThat(result.events()).contains(new CardsDrawn(0, 1, DrawReason.PENALTY, 2, state.drawPile().size()));
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(1, 1, 0));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void a_king_still_gives_the_owner_another_turn_after_being_caught() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(PlayingCard.KING)), 0,
                PlayCard.of(heart(PlayingCard.KING)), NOW).newState();

        OneCardState after = engine.apply(raced, 1, new Catch(raced.race().raceId()), NOW + 500).newState();

        assertThat(after.turnSeat()).isZero();
        assertThat(after.hands().get(0)).hasSize(2);
    }

    @Test
    void a_jack_still_skips_and_an_attack_still_lands_after_the_race() {
        OneCardEngine engine = humans(3);
        OneCardState jack = engine.apply(aboutToGoDownToOne(heart(PlayingCard.JACK)), 0,
                PlayCard.of(heart(PlayingCard.JACK)), NOW).newState();
        OneCardState attack = engine.apply(aboutToGoDownToOne(heart(2)), 0, PlayCard.of(heart(2)), NOW).newState();

        assertThat(engine.apply(jack, 0, new CallOneCard(jack.race().raceId()), NOW).newState().turnSeat())
                .isEqualTo(2);
        OneCardEngine.Result attacked = engine.apply(attack, 0, new CallOneCard(attack.race().raceId()), NOW);
        assertThat(attacked.events().getLast()).isEqualTo(new TurnChanged(1, 1, 2));
        assertThat(attacked.newState().attackStack()).isEqualTo(2);
    }

    @Test
    void presses_are_checked_against_the_window() {
        OneCardEngine engine = humans(3);
        OneCardState calm = aboutToGoDownToOne(heart(9));
        OneCardState raced = engine.apply(calm, 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        assertThat(rejection(engine, calm, 1, new Catch(0))).isEqualTo(RejectionReason.NO_RACE);
        assertThat(rejection(engine, raced, 1, new Catch(raceId + 1))).isEqualTo(RejectionReason.NO_RACE);
        assertThat(rejection(engine, raced, 1, new CallOneCard(raceId))).isEqualTo(RejectionReason.NOT_RACE_OWNER);
        assertThat(rejection(engine, raced, 0, new Catch(raceId))).isEqualTo(RejectionReason.OWNER_CANNOT_CATCH);
        assertThat(rejection(engine, raced, 1, PlayCard.of(spade(4)))).isEqualTo(RejectionReason.RACE_IN_PROGRESS);
        assertThat(rejection(engine, raced, 1, new Draw())).isEqualTo(RejectionReason.RACE_IN_PROGRESS);
    }

    @Test
    void during_a_race_the_owner_may_call_and_everyone_else_may_catch() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();
        int raceId = raced.race().raceId();

        assertThat(engine.legalActions(raced, 0)).containsExactly(new CallOneCard(raceId));
        assertThat(engine.legalActions(raced, 2)).containsExactly(new Catch(raceId));
    }

    @Test
    void without_bots_the_window_expires_after_its_length() {
        OneCardEngine engine = humans(3);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 3_000);
        OneCardEngine.Result expired = engine.onTimer(raced).orElseThrow();
        assertThat(expired.events()).containsExactly(
                new RaceResolved(raced.race().raceId(), RaceOutcome.EXPIRED, -1),
                new TurnChanged(1, 1, 0));
    }

    @Test
    void a_bot_owner_calls_after_its_reaction_time() {
        OneCardEngine engine = engine(3, FIXED, 0, 2);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 1_200);
        assertThat(engine.onTimer(raced).orElseThrow().events().getFirst())
                .isEqualTo(new RaceResolved(raced.race().raceId(), RaceOutcome.CALLED, 0));
    }

    @Test
    void a_bot_catches_a_human_owner_who_is_too_slow() {
        OneCardEngine engine = engine(3, FIXED, 2);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 1_500);
        OneCardEngine.Result caught = engine.onTimer(raced).orElseThrow();
        assertThat(caught.events().getFirst())
                .isEqualTo(new RaceResolved(raced.race().raceId(), RaceOutcome.CAUGHT, 2));
        assertThat(caught.newState().hands().get(0)).hasSize(2);
    }

    @Test
    void a_bot_slower_than_the_window_never_presses() {
        OneCardEngine engine = engine(3, new RaceSettings(3_000, 1_000, 1_000, 3_500, 3_500, 8), 2);
        OneCardState raced = engine.apply(aboutToGoDownToOne(heart(9)), 0, PlayCard.of(heart(9)), NOW).newState();

        assertThat(raced.race().botPress()).isNull();
        assertThat(engine.timerDeadline(raced)).hasValue(NOW + 3_000);
    }

    @Test
    void there_is_no_timer_outside_a_race() {
        OneCardEngine engine = humans(3);
        OneCardState calm = aboutToGoDownToOne(heart(9));

        assertThat(engine.timerDeadline(calm)).isEmpty();
        assertThat(engine.onTimer(calm)).isEmpty();
    }
}
