package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.BLACK_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.COLOR_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.bot.OneCardBotPolicy;
import com.mirboard.domain.game.onecard.bot.OneCardBotView;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** D-128 — 휴리스틱 봇의 수 선택(설계서 §4.6)과 봇이 보는 정보의 경계. */
class OneCardBotPolicyTest {

    private static OneCardAction choose(OneCardState state, int seat) {
        List<Long> ids = LongStream.range(0, state.seatCount()).map(i -> 100 + i).boxed().toList();
        OneCardEngine engine = new OneCardEngine(new GameContext("bot", ids), new Random(1));
        return OneCardBotPolicy.choose(OneCardBotView.of(state, seat), engine.legalActions(state, seat));
    }

    /** 다음 사람(좌석 1)이 넉넉히 든 3인 테이블 — 압박할 이유가 없다. */
    private static OneCardTables calm(PlayingCard... mine) {
        return seats(hand(mine), hand(spade(10), club(10), diamond(10), heart(10)),
                hand(club(4), club(6), club(8)));
    }

    @Test
    void under_attack_the_weakest_counter_is_played() {
        OneCardState state = seats(hand(BLACK_JOKER, spade(2), heart(9)), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(spade(2)));
    }

    @Test
    void under_attack_without_a_counter_the_bot_draws() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(new Draw());
    }

    @Test
    void a_number_card_goes_before_specials_attacks_and_jokers() {
        OneCardState state = calm(heart(PlayingCard.ACE), heart(PlayingCard.KING), COLOR_JOKER, heart(9))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(heart(9)));
    }

    @Test
    void among_number_cards_the_suit_the_bot_holds_most_goes_first() {
        OneCardState state = calm(heart(9), spade(5), spade(6), spade(8))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(spade(5)));
    }

    @Test
    void a_next_player_close_to_finishing_meets_an_attack_first() {
        OneCardState state = seats(hand(heart(9), heart(PlayingCard.JACK), heart(2)), hand(spade(4)),
                hand(club(4), club(6), club(8)))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(heart(2)));
    }

    @Test
    void without_an_attack_card_a_jack_holds_the_threat_back() {
        OneCardState state = seats(hand(heart(9), heart(PlayingCard.JACK)), hand(spade(4), spade(6)),
                hand(club(4), club(6), club(8)))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(heart(PlayingCard.JACK)));
    }

    @Test
    void a_seven_names_the_suit_the_bot_holds_most() {
        OneCardState state = calm(heart(7), spade(4), spade(6), club(3))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(new PlayCard(heart(7), Suit.SPADE));
    }

    @Test
    void the_view_holds_the_bots_own_hand_public_counts_and_the_next_live_seat() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6)), hand(),
                hand(diamond(4), diamond(6), diamond(8)))
                .eliminated(2, Elimination.Reason.BANKRUPT, 20).top(heart(5)).direction(-1).turn(0).build();

        OneCardBotView view = OneCardBotView.of(state, 0);

        assertThat(view.hand()).containsExactly(heart(9), club(3));
        assertThat(view.handCounts()).containsExactly(2, 2, 0, 3);
        assertThat(view.nextSeat()).isEqualTo(3);
        assertThat(view.nextHandCount()).isEqualTo(3);
        assertThat(view.baseSuit()).isEqualTo(Suit.HEART);
    }
}
