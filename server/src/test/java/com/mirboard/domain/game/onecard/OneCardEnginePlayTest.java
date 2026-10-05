package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.BLACK_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.action.OneCardActionRejectedException;
import com.mirboard.domain.game.onecard.action.RejectionReason;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardPlayed;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardsDrawn;
import com.mirboard.domain.game.onecard.event.OneCardEvent.DrawReason;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandDealt;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandUpdated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.MatchEnded;
import com.mirboard.domain.game.onecard.event.OneCardEvent.MatchStarted;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PileReshuffled;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PlayerEliminated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.TurnChanged;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §2~§8, §10~§11 — 시작·내기·먹기·파산·종료. 경쟁 창은 {@code OneCardEngineRaceTest}. */
class OneCardEnginePlayTest {

    private static final long NOW = 1_000L;

    private static OneCardEngine engine(int seats, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        return new OneCardEngine(new GameContext("r", ids, 0, 0, List.of(botSeats)), new Random(7));
    }

    private static OneCardState play(OneCardEngine engine, OneCardState state, int seat, PlayingCard card) {
        return engine.apply(state, seat, PlayCard.of(card), NOW).newState();
    }

    private static RejectionReason rejection(OneCardEngine engine, OneCardState state, int seat,
                                             OneCardAction action) {
        try {
            engine.apply(state, seat, action, NOW);
        } catch (OneCardActionRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("expected a rejection");
    }

    // ---------- 시작 (§2, §3) ----------

    @Test
    void start_deals_seven_each_flips_a_normal_card_and_announces_the_first_turn() {
        OneCardEngine.Result result = engine(4).startMatch();
        OneCardState state = result.newState();

        assertThat(state.hands()).allSatisfy(h -> assertThat(h).hasSize(Dealer.HAND_SIZE));
        assertThat(state.topCard().isNormal()).isTrue();
        assertThat(result.events().getFirst())
                .isEqualTo(new MatchStarted(state.turnSeat(), state.topCard(), 7, state.drawPile().size()));
        assertThat(result.events()).filteredOn(HandDealt.class::isInstance).hasSize(4)
                .allSatisfy(e -> assertThat(e.isPrivate()).isTrue());
        for (int seat = 0; seat < 4; seat++) {
            int owner = seat;
            assertThat(result.events()).filteredOn(e -> e instanceof HandDealt && e.privateSeat() == owner)
                    .containsExactly(new HandDealt(owner, state.hands().get(owner), 1));
        }
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(state.turnSeat(), 1, 0));
        OneCardInvariantChecker.check(state);
    }

    @Test
    void the_first_seat_is_random() {
        Set<Integer> firstSeats = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            List<Long> ids = List.of(1L, 2L, 3L, 4L);
            OneCardEngine engine = new OneCardEngine(new GameContext("r", ids), new Random(seed));
            firstSeats.add(engine.startMatch().newState().turnSeat());
        }
        assertThat(firstSeats).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    // ---------- 내기 (§5, §8) ----------

    @Test
    void a_normal_card_passes_the_turn_and_only_the_hand_event_is_private() {
        OneCardState state = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, PlayCard.of(heart(9)), NOW);

        assertThat(result.newState().topCard()).isEqualTo(heart(9));
        assertThat(result.newState().turnSeat()).isEqualTo(1);
        assertThat(result.events()).containsExactly(
                new CardPlayed(0, heart(9), null, 2, 0, 1),
                new HandUpdated(0, List.of(club(3), club(4)), List.of(), 2),
                new TurnChanged(1, 1, 0));
        assertThat(result.events()).filteredOn(OneCardEvent::isPrivate).hasSize(1);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void a_seven_sets_the_base_suit_for_the_next_player() {
        OneCardState state = seats(hand(heart(7), club(3), club(4)), hand(spade(4), heart(9), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(2);

        OneCardState next = engine.apply(state, 0, new PlayCard(heart(7), Suit.SPADE), NOW).newState();

        assertThat(next.declaredSuit()).isEqualTo(Suit.SPADE);
        assertThat(rejection(engine, next, 1, PlayCard.of(heart(9)))).isEqualTo(RejectionReason.CARD_NOT_PLAYABLE);
        assertThat(play(engine, next, 1, spade(4)).declaredSuit()).isNull();
    }

    @Test
    void a_suit_declaration_is_required_on_a_seven_and_forbidden_elsewhere() {
        OneCardState state = seats(hand(heart(7), heart(9), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(2);

        assertThat(rejection(engine, state, 0, PlayCard.of(heart(7))))
                .isEqualTo(RejectionReason.INVALID_SUIT_DECLARATION);
        assertThat(rejection(engine, state, 0, new PlayCard(heart(9), Suit.CLUB)))
                .isEqualTo(RejectionReason.INVALID_SUIT_DECLARATION);
    }

    @Test
    void basic_rejections_name_the_reason() {
        OneCardState state = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(2);

        assertThat(rejection(engine, state, 1, PlayCard.of(spade(4)))).isEqualTo(RejectionReason.NOT_YOUR_TURN);
        assertThat(rejection(engine, state, 0, PlayCard.of(spade(4)))).isEqualTo(RejectionReason.CARD_NOT_OWNED);
        assertThat(rejection(engine, state, 0, PlayCard.of(club(3)))).isEqualTo(RejectionReason.CARD_NOT_PLAYABLE);
    }

    @Test
    void an_attack_passes_to_the_next_seat_which_must_counter_or_draw_the_stack() {
        OneCardState state = seats(hand(heart(2), club(3), club(4)), hand(spade(2), heart(9), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(3);

        OneCardEngine.Result attacked = engine.apply(state, 0, PlayCard.of(heart(2)), NOW);
        assertThat(attacked.events().getLast()).isEqualTo(new TurnChanged(1, 1, 2));
        assertThat(rejection(engine, attacked.newState(), 1, PlayCard.of(heart(9))))
                .isEqualTo(RejectionReason.COUNTER_REQUIRED);

        OneCardState countered = play(engine, attacked.newState(), 1, spade(2));
        assertThat(countered.attackStack()).isEqualTo(4);
        assertThat(countered.turnSeat()).isEqualTo(2);

        OneCardEngine.Result drew = engine.apply(countered, 2, new Draw(), NOW);
        assertThat(drew.newState().hands().get(2)).hasSize(7);
        assertThat(drew.newState().attackStack()).isZero();
        assertThat(drew.newState().turnSeat()).isEqualTo(0);
        assertThat(drew.events()).contains(new CardsDrawn(2, 4, DrawReason.ATTACK, 7,
                drew.newState().drawPile().size()));
    }

    @Test
    void a_jack_skips_the_next_seat() {
        OneCardState state = seats(hand(heart(PlayingCard.JACK), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();

        assertThat(play(engine(3), state, 0, heart(PlayingCard.JACK)).turnSeat()).isEqualTo(2);
    }

    @Test
    void a_queen_reverses_the_direction() {
        OneCardState state = seats(hand(heart(PlayingCard.QUEEN), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();

        OneCardEngine.Result result = engine(3).apply(state, 0, PlayCard.of(heart(PlayingCard.QUEEN)), NOW);

        assertThat(result.newState().direction()).isEqualTo(-1);
        assertThat(result.newState().turnSeat()).isEqualTo(2);
        assertThat(result.events()).contains(new CardPlayed(0, heart(PlayingCard.QUEEN), null, 2, 0, -1));
    }

    @Test
    void a_king_gives_another_turn_and_drawing_then_ends_it() {
        OneCardState state = seats(hand(heart(PlayingCard.KING), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)), hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
        OneCardEngine engine = engine(3);

        OneCardState again = play(engine, state, 0, heart(PlayingCard.KING));
        assertThat(again.turnSeat()).isZero();

        assertThat(engine.apply(again, 0, new Draw(), NOW).newState().turnSeat()).isEqualTo(1);
    }

    @Test
    void with_two_players_jack_and_queen_give_another_turn() {
        OneCardEngine engine = engine(2);
        OneCardState jack = seats(hand(heart(PlayingCard.JACK), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();
        OneCardState queen = seats(hand(heart(PlayingCard.QUEEN), club(3), club(4)),
                hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        assertThat(play(engine, jack, 0, heart(PlayingCard.JACK)).turnSeat()).isZero();
        OneCardState afterQueen = play(engine, queen, 0, heart(PlayingCard.QUEEN));
        assertThat(afterQueen.turnSeat()).isZero();
        assertThat(afterQueen.direction()).isEqualTo(-1);
    }

    // ---------- 먹기 (§7) ----------

    @Test
    void drawing_takes_one_card_even_when_a_card_could_be_played() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).drawPile(diamond(9), diamond(10)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, new Draw(), NOW);

        assertThat(result.newState().hands().get(0)).containsExactly(heart(9), club(3), diamond(9));
        assertThat(result.events()).containsExactly(
                new CardsDrawn(0, 1, DrawReason.TURN, 3, 1),
                new HandUpdated(0, List.of(heart(9), club(3), diamond(9)), List.of(diamond(9)), 2),
                new TurnChanged(1, 1, 0));
    }

    @Test
    void an_empty_pile_is_refilled_from_the_discard_pile_keeping_the_top() {
        OneCardState state = seats(hand(club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).drawPile(diamond(9)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, new Draw(), NOW);

        assertThat(result.newState().hands().get(0)).hasSize(4);
        assertThat(result.newState().discardPile()).containsExactly(heart(2));
        assertThat(result.events()).anyMatch(PileReshuffled.class::isInstance);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void with_nothing_left_to_draw_the_turn_is_a_pass_and_everyone_passing_is_a_stalemate() {
        OneCardState state = everyCardInHands(3, heart(5));
        OneCardEngine engine = engine(3);

        OneCardEngine.Result first = engine.apply(state, 0, new Draw(), NOW);
        assertThat(first.newState().passStreak()).isEqualTo(1);
        assertThat(first.events()).contains(new CardsDrawn(0, 0, DrawReason.TURN, 18, 0));

        OneCardState second = engine.apply(first.newState(), 1, new Draw(), NOW).newState();
        OneCardEngine.Result third = engine.apply(second, 2, new Draw(), NOW);
        assertThat(third.newState().result().reason()).isEqualTo(EndReason.STALEMATE);
        assertThat(third.events().getLast()).isInstanceOf(MatchEnded.class);
    }

    @Test
    void an_attack_larger_than_what_is_left_draws_only_what_is_there_and_is_not_a_pass() {
        // OneCardTables 는 남는 카드를 버린 더미에 넣어 다시 채워지므로 직접 만든다 — 뽑을 더미 2장, 버린 더미는
        // 맨 위 흑백 조커뿐이라 다시 채울 카드가 없다. 3인 × 17장 + 2 + 1 = 54.
        List<PlayingCard> others = Deck.all().stream().filter(card -> !card.equals(BLACK_JOKER)).toList();
        OneCardState state = new OneCardState(
                List.of(others.subList(2, 19), others.subList(19, 36), others.subList(36, 53)),
                others.subList(0, 2), List.of(BLACK_JOKER), 0, 1, null, 5, null, List.of(), 0, 0, 1, null);

        OneCardEngine.Result result = engine(3).apply(state, 0, new Draw(), NOW);

        OneCardState next = result.newState();
        assertThat(result.events()).containsExactly(
                new CardsDrawn(0, 2, DrawReason.ATTACK, 19, 0),
                new HandUpdated(0, next.hands().get(0), others.subList(0, 2), 2),
                new TurnChanged(1, 1, 0));
        assertThat(next.passStreak()).isZero();
        assertThat(next.attackStack()).isZero();
        OneCardInvariantChecker.check(next);
    }

    @Test
    void playing_or_drawing_a_card_resets_the_pass_streak() {
        OneCardEngine engine = engine(3);
        OneCardState passing = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).passStreak(2).build();

        OneCardState played = play(engine, passing, 0, heart(9));
        OneCardState drew = engine.apply(passing, 0, new Draw(), NOW).newState();

        assertThat(played.passStreak()).isZero();
        assertThat(drew.passStreak()).isZero();
        OneCardInvariantChecker.check(played);
        OneCardInvariantChecker.check(drew);
    }

    /** 맨 위 한 장을 빼고 53장을 전부 손패로 나눈 상태 — 뽑을 더미도, 다시 채울 버린 더미도 없다. */
    private static OneCardState everyCardInHands(int seatCount, PlayingCard top) {
        List<List<PlayingCard>> hands = new ArrayList<>();
        for (int seat = 0; seat < seatCount; seat++) {
            hands.add(new ArrayList<>());
        }
        int next = 0;
        for (PlayingCard card : Deck.all()) {
            if (!card.equals(top)) {
                hands.get(next++ % seatCount).add(card);
            }
        }
        return new OneCardState(hands, List.of(), List.of(top), 0, 1, null, 0, null, List.of(), 0, 0, 1, null);
    }

    /** 좌석 0 의 19장 — ♣A~♣K 와 ♦A·3·4·6·8·9. 뽑을 더미 ♦10·♦J 에서 한 장을 먹으면 20장이 돼 파산한다(§10). */
    private static List<PlayingCard> nineteenCards() {
        List<PlayingCard> cards = new ArrayList<>(IntStream.rangeClosed(1, 13).mapToObj(OneCardTables::club).toList());
        cards.addAll(List.of(diamond(1), diamond(3), diamond(4), diamond(6), diamond(8), diamond(9)));
        return cards;
    }

    /** 2인 — 좌석 0 이 다음 먹기에서 파산한다. */
    private static OneCardTables aboutToGoBankrupt() {
        return seats(nineteenCards(), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).drawPile(diamond(10), diamond(11)).turn(0);
    }

    /** 3인 — 좌석 0 이 다음 먹기에서 파산한다. 좌석 1·2 는 3장씩이라 순위에서 동순위다. */
    private static OneCardTables threeSeatsAboutToGoBankrupt() {
        return seats(nineteenCards(), hand(spade(4), spade(6), spade(8)), hand(heart(9), heart(10), heart(3)))
                .top(heart(5)).drawPile(diamond(10), diamond(11)).turn(0);
    }

    @Test
    void reaching_twenty_cards_is_bankruptcy_and_two_players_leaves_one_standing() {
        OneCardState state = aboutToGoBankrupt().build();

        OneCardEngine.Result result = engine(2).apply(state, 0, new Draw(), NOW);

        OneCardState next = result.newState();
        List<PlayingCard> twenty = new ArrayList<>(nineteenCards());
        twenty.add(diamond(10));
        List<PlayingCard> pileAfter = new ArrayList<>(List.of(diamond(11)));
        pileAfter.addAll(twenty);
        assertThat(next.hands().get(0)).isEmpty();
        // 손패는 섞지 않고 받은 순서 그대로 뽑을 더미 맨 아래로 간다(§10).
        assertThat(next.drawPile()).containsExactlyElementsOf(pileAfter).hasSize(21);
        assertThat(next.eliminations()).containsExactly(new Elimination(0, Elimination.Reason.BANKRUPT, 20));
        assertThat(result.events()).containsSequence(
                new HandUpdated(0, twenty, List.of(diamond(10)), 2),
                new PlayerEliminated(0, Elimination.Reason.BANKRUPT, 20, 21),
                new HandUpdated(0, List.of(), List.of(), 3));
        assertThat(next.version()).isEqualTo(3);
        assertThat(next.result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(next.result().winners()).containsExactly(1);
        OneCardInvariantChecker.check(next);
    }

    @Test
    void bankruptcy_on_the_turn_limit_ends_as_last_standing_not_a_stalemate() {
        OneCardState state = aboutToGoBankrupt().turnCount(OneCardEngine.TURN_LIMIT - 1).build();

        OneCardState next = engine(2).apply(state, 0, new Draw(), NOW).newState();

        assertThat(next.turnCount()).isEqualTo(OneCardEngine.TURN_LIMIT);
        assertThat(next.result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(next.result().winners()).containsExactly(1);
        OneCardInvariantChecker.check(next);
    }

    @Test
    void a_bot_left_alone_by_a_bankruptcy_is_last_standing_not_no_humans() {
        OneCardState next = engine(2, 1).apply(aboutToGoBankrupt().build(), 0, new Draw(), NOW).newState();

        assertThat(next.result().reason()).isEqualTo(EndReason.LAST_STANDING);
        assertThat(next.result().winners()).containsExactly(1);
        OneCardInvariantChecker.check(next);
    }

    @Test
    void when_a_bankruptcy_leaves_only_bots_the_match_ends_as_no_humans_with_the_bankrupt_seat_last() {
        OneCardState next = engine(3, 1, 2).apply(threeSeatsAboutToGoBankrupt().build(), 0, new Draw(), NOW)
                .newState();

        assertThat(next.result().reason()).isEqualTo(EndReason.NO_HUMANS);
        assertThat(next.result().standings()).containsExactly(
                new Standing(1, 1, 3, SeatStatus.ALIVE),
                new Standing(2, 1, 3, SeatStatus.ALIVE),
                new Standing(0, 3, 20, SeatStatus.BANKRUPT));
        OneCardInvariantChecker.check(next);
    }

    @Test
    void a_bankruptcy_among_three_humans_passes_the_turn_on_and_the_match_goes_on() {
        OneCardEngine.Result result = engine(3).apply(threeSeatsAboutToGoBankrupt().build(), 0, new Draw(), NOW);

        OneCardState next = result.newState();
        assertThat(next.ended()).isFalse();
        assertThat(next.eliminations()).containsExactly(new Elimination(0, Elimination.Reason.BANKRUPT, 20));
        assertThat(result.events().getLast()).isEqualTo(new TurnChanged(1, 1, 0));
        assertThat(next.turnSeat()).isEqualTo(1);
        assertThat(next.passStreak()).isZero();
        OneCardInvariantChecker.check(next);
    }

    // ---------- 종료 (§11) ----------

    @Test
    void playing_the_last_card_wins_even_if_it_is_an_attack_card() {
        OneCardState state = seats(hand(heart(2)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, PlayCard.of(heart(2)), NOW);

        assertThat(result.newState().result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(result.newState().result().winners()).containsExactly(0);
        assertThat(result.newState().turnSeat()).isEqualTo(-1);
        assertThat(engine(2).pendingSeats(result.newState())).isEmpty();
    }

    @Test
    void the_turn_limit_ends_the_match_as_a_stalemate() {
        OneCardState state = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).turnCount(OneCardEngine.TURN_LIMIT - 1).build();

        OneCardState next = play(engine(2), state, 0, heart(9));

        assertThat(next.result().reason()).isEqualTo(EndReason.STALEMATE);
    }

    @Test
    void finishing_with_the_last_card_on_the_turn_limit_is_a_win_not_a_stalemate() {
        OneCardState state = seats(hand(heart(9)), hand(spade(4), spade(6)))
                .top(heart(5)).turn(0).turnCount(OneCardEngine.TURN_LIMIT - 1).build();

        OneCardState next = play(engine(2), state, 0, heart(9));

        assertThat(next.turnCount()).isEqualTo(OneCardEngine.TURN_LIMIT);
        assertThat(next.result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(next.result().winners()).containsExactly(0);
        OneCardInvariantChecker.check(next);
    }

    @Test
    void drawing_on_the_turn_limit_ends_as_a_stalemate_without_announcing_another_turn() {
        OneCardState state = seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).turnCount(OneCardEngine.TURN_LIMIT - 1).build();

        OneCardEngine.Result result = engine(2).apply(state, 0, new Draw(), NOW);

        assertThat(result.newState().turnCount()).isEqualTo(OneCardEngine.TURN_LIMIT);
        assertThat(result.newState().result().reason()).isEqualTo(EndReason.STALEMATE);
        assertThat(result.events().getLast()).isInstanceOf(MatchEnded.class);
        assertThat(result.events()).noneMatch(TurnChanged.class::isInstance);
        OneCardInvariantChecker.check(result.newState());
    }

    @Test
    void after_the_end_and_for_eliminated_seats_actions_are_rejected() {
        OneCardEngine engine = engine(3);
        OneCardState ended = engine.apply(seats(hand(heart(2)), hand(spade(4), spade(6)), hand(club(9), club(10)))
                .top(heart(5)).turn(0).build(), 0, PlayCard.of(heart(2)), NOW).newState();
        OneCardState withOut = seats(hand(heart(9), club(3), club(4)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5)).turn(0).build();

        assertThat(rejection(engine, ended, 1, new Draw())).isEqualTo(RejectionReason.MATCH_OVER);
        assertThat(rejection(engine, withOut, 1, new Draw())).isEqualTo(RejectionReason.PLAYER_ELIMINATED);
    }

    // ---------- 봇·타임아웃용 질의 ----------

    @Test
    void legal_actions_list_every_playable_card_each_seven_suit_and_drawing() {
        OneCardState state = seats(hand(heart(9), heart(7), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        List<OneCardAction> legal = engine(2).legalActions(state, 0);

        assertThat(legal).containsExactlyInAnyOrder(
                PlayCard.of(heart(9)),
                new PlayCard(heart(7), Suit.SPADE), new PlayCard(heart(7), Suit.HEART),
                new PlayCard(heart(7), Suit.DIAMOND), new PlayCard(heart(7), Suit.CLUB),
                new Draw());
        assertThat(engine(2).legalActions(state, 1)).isEmpty();
    }

    @Test
    void under_attack_only_counters_and_drawing_are_legal() {
        OneCardState state = seats(hand(spade(2), heart(9), BLACK_JOKER), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).turn(0).build();

        assertThat(engine(2).legalActions(state, 0)).containsExactlyInAnyOrder(
                PlayCard.of(spade(2)), PlayCard.of(BLACK_JOKER), new Draw());
    }

    @Test
    void the_timeout_action_is_drawing_for_the_seat_on_turn_only() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(5)).turn(0).build();

        assertThat(engine(2).timeoutAction(state, 0)).isEqualTo(new Draw());
        assertThat(engine(2).timeoutAction(state, 1)).isNull();
    }
}
