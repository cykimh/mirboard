package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.OneCardEngine.Desertion;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandUpdated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PlayerEliminated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardEvent.TurnChanged;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §10, §9.2, §11 — 탈주. */
class OneCardEngineDesertionTest {

    private static final long NOW = 5_000L;

    private static OneCardEngine engine(int seats, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        return new OneCardEngine(new GameContext("r", ids, 0, 0, Arrays.asList(botSeats)), new Random(3));
    }

    private static OneCardState fourSeats(int turn) {
        return seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(turn).build();
    }

    /** 4인 테이블에서 좌석 0 이 {@code card} 를 내 1장(♣3)이 된 경쟁 창 — 주인 0, 다음 차례는 카드 효과가 정한다. */
    private static OneCardState raceAfterPlaying(OneCardEngine engine, PlayingCard card, int direction) {
        OneCardState table = seats(hand(card, club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).direction(direction).turn(0).build();
        return engine.apply(table, 0, PlayCard.of(card), NOW).newState();
    }

    private static void assertNotADesertion(Desertion result, OneCardState unchanged) {
        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.NOT_APPLICABLE);
        assertThat(result.newState()).isSameAs(unchanged);
        assertThat(result.events()).isEmpty();
    }

    @Test
    void an_eliminated_seat_or_a_finished_match_is_not_a_desertion() {
        OneCardEngine engine = engine(3);
        OneCardState withOut = seats(hand(heart(9), club(3)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5)).turn(0).build();
        OneCardState ended = engine.apply(seats(hand(heart(2)), hand(spade(4)), hand(club(9)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();

        assertNotADesertion(engine.desert(withOut, 1), withOut);
        assertNotADesertion(engine.desert(ended, 2), ended);
    }

    @Test
    void out_of_range_seats_and_a_seat_that_just_left_are_not_a_desertion() {
        OneCardEngine engine = engine(4);
        OneCardState table = fourSeats(0);
        OneCardState afterLeaving = engine.desert(table, 2).newState();

        assertNotADesertion(engine.desert(table, -1), table);
        assertNotADesertion(engine.desert(table, 4), table);
        assertNotADesertion(engine.desert(afterLeaving, 2), afterLeaving);
    }

    @Test
    void a_deserter_on_turn_drops_the_attack_and_the_next_live_seat_plays_freely() {
        OneCardState attacked = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(2)).attack(2).turn(1).build();

        Desertion result = engine(3).desert(attacked, 1);

        OneCardState state = result.newState();
        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
        assertThat(state.attackStack()).isZero();
        assertThat(state.turnSeat()).isEqualTo(2);
        assertThat(state.hands().get(1)).isEmpty();
        assertThat(state.drawPile()).endsWith(spade(4), spade(6), spade(8));
        assertThat(result.events()).containsExactly(
                new PlayerEliminated(1, Elimination.Reason.DESERTED, 3, 47),
                new HandUpdated(1, List.of(), List.of(), state.version()),
                new TurnChanged(2, 1, 0));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void someone_else_deserting_keeps_the_current_turn() {
        Desertion result = engine(4).desert(fourSeats(0), 2);

        assertThat(result.newState().turnSeat()).isZero();
        assertThat(result.events()).noneMatch(TurnChanged.class::isInstance);
    }

    @Test
    void a_bystander_deserting_leaves_the_pending_attack_and_the_turn_alone() {
        OneCardState attacked = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(2)).attack(2).turn(1).build();

        Desertion result = engine(4).desert(attacked, 3);

        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
        assertThat(result.newState().attackStack()).isEqualTo(2);
        assertThat(result.newState().turnSeat()).isEqualTo(1);
        assertThat(result.events()).noneMatch(TurnChanged.class::isInstance);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void a_desertion_resets_the_pass_streak() {
        OneCardState passing = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).passStreak(2).turn(0).build();

        assertThat(engine(3).desert(passing, 2).newState().passStreak()).isZero();
    }

    @Test
    void during_a_race_the_window_closes_without_penalty_and_the_reserved_seat_plays() {
        OneCardEngine engine = engine(4);
        OneCardState raced = engine.apply(seats(hand(heart(2), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();
        int raceId = raced.race().raceId();

        Desertion result = engine.desert(raced, 3);

        assertThat(result.events().getFirst()).isEqualTo(new RaceResolved(raceId, RaceOutcome.CANCELLED, -1));
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(1, 1, 2));
        assertThat(result.newState().hands().get(0)).hasSize(1);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void the_owner_deserting_during_an_attack_race_still_passes_the_attack_to_the_reserved_seat() {
        OneCardEngine engine = engine(4);
        OneCardState raced = raceAfterPlaying(engine, heart(2), 1);
        int raceId = raced.race().raceId();

        Desertion result = engine.desert(raced, 0);

        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
        assertThat(result.events().getFirst()).isEqualTo(new RaceResolved(raceId, RaceOutcome.CANCELLED, -1));
        assertThat(result.events()).filteredOn(PlayerEliminated.class::isInstance)
                .containsExactly(new PlayerEliminated(0, Elimination.Reason.DESERTED, 1, raced.drawPile().size() + 1));
        assertThat(result.newState().attackStack()).isEqualTo(2);
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(1, 1, 2));
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void if_the_seat_a_jack_skipped_to_deserts_during_the_race_the_next_live_seat_plays() {
        OneCardEngine engine = engine(4);
        OneCardState raced = raceAfterPlaying(engine, heart(PlayingCard.JACK), 1);
        assertThat(raced.race().nextSeat()).isEqualTo(2);

        Desertion result = engine.desert(raced, 2);

        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(3, 1, 0));
        assertThat(result.newState().turnSeat()).isEqualTo(3);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void deserting_passes_the_turn_on_in_the_reversed_direction_too() {
        OneCardEngine engine = engine(4);
        OneCardState reversed = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).direction(-1).turn(2).build();
        OneCardState reversedRace = raceAfterPlaying(engine, heart(2), -1);
        assertThat(reversedRace.race().nextSeat()).isEqualTo(3);

        Desertion onTurn = engine.desert(reversed, 2);
        Desertion inRace = engine.desert(reversedRace, 3);

        assertThat(onTurn.events().getLast()).isEqualTo(new TurnChanged(1, -1, 0));
        assertThat(inRace.events().getLast()).isEqualTo(new TurnChanged(2, -1, 0));
        OneCardInvariantChecker.check(onTurn.newState());
        OneCardInvariantChecker.check(inRace.newState());
    }

    @Test
    void if_the_reserved_seat_deserts_during_a_race_the_next_seat_plays_without_the_attack() {
        OneCardEngine engine = engine(4);
        OneCardState raced = engine.apply(seats(hand(heart(2), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)), hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();

        Desertion result = engine.desert(raced, 1);

        assertThat(result.newState().attackStack()).isZero();
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(2, 1, 0));
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void if_a_king_owner_deserts_during_the_race_the_next_live_seat_plays() {
        OneCardEngine engine = engine(4);
        OneCardState raced = engine.apply(seats(hand(heart(PlayingCard.KING), club(3)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)),
                hand(club(9), club(10), club(11)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(PlayingCard.KING)), NOW).newState();

        Desertion result = engine.desert(raced, 0);

        assertThat(result.newState().turnSeat()).isEqualTo(1);
        assertThat(result.newState().race()).isNull();
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void the_last_seat_standing_wins_and_the_deserter_ranks_last() {
        OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        Desertion result = engine(2).desert(twoSeats, 1);

        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
        assertThat(result.newState().result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(result.newState().result().standings()).containsExactly(
                new Standing(0, 1, 2, SeatStatus.ALIVE),
                new Standing(1, 2, 3, SeatStatus.DESERTED));
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void the_last_standing_bot_beats_no_humans_when_a_human_deserts_a_two_seat_table() {
        OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        Desertion result = engine(2, 1).desert(twoSeats, 0);

        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
        assertThat(result.newState().result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(result.newState().result().winners()).containsExactly(1);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void when_no_human_is_left_alive_the_match_ends() {
        // 봇은 남은 장수 순으로, 탈주한 사람은 맨 아래로 순위가 매겨진다.
        OneCardState table = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6)), hand(club(9), club(10), club(11), club(12)))
                .top(heart(5)).turn(1).build();

        Desertion result = engine(4, 1, 2, 3).desert(table, 0);

        assertThat(result.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
        assertThat(result.newState().result().reason()).isEqualTo(EndReason.NO_HUMANS);
        assertThat(result.newState().result().standings()).containsExactly(
                new Standing(2, 1, 2, SeatStatus.ALIVE),
                new Standing(1, 2, 3, SeatStatus.ALIVE),
                new Standing(3, 3, 4, SeatStatus.ALIVE),
                new Standing(0, 4, 3, SeatStatus.DESERTED));
        OneCardInvariantChecker.check(result.newState());
    }
}
