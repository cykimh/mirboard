package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** `docs/rules-onecard.md` §3 — 분배와 시작 카드. */
class DealerTest {

    private static final PlayingCard SPADE_2 = PlayingCard.of(Suit.SPADE, 2);
    private static final PlayingCard HEART_5 = PlayingCard.of(Suit.HEART, 5);

    @ParameterizedTest(name = "{0}인")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void deals_seven_each_and_flips_a_normal_start_card(int seats) {
        Dealer.Deal deal = Dealer.deal(seats, Dealer.random(new Random(seats)));

        assertThat(deal.hands()).hasSize(seats).allSatisfy(hand -> assertThat(hand).hasSize(Dealer.HAND_SIZE));
        assertThat(deal.startCard().isNormal()).isTrue();
        List<PlayingCard> all = new ArrayList<>(deal.drawPile());
        deal.hands().forEach(all::addAll);
        all.add(deal.startCard());
        assertThat(all).hasSize(Deck.SIZE).doesNotHaveDuplicates();
    }

    @Test
    void a_non_normal_top_goes_to_the_bottom_and_the_next_card_is_flipped() {
        List<PlayingCard> order = new ArrayList<>(Deck.all());
        order.remove(SPADE_2);
        order.remove(HEART_5);
        order.add(14, SPADE_2);
        order.add(15, HEART_5);

        Dealer.Deal deal = Dealer.deal(2, cards -> order);

        assertThat(deal.startCard()).isEqualTo(HEART_5);
        assertThat(deal.drawPile()).hasSize(Deck.SIZE - 14 - 1).endsWith(SPADE_2);
    }

    @Test
    void when_the_pile_has_no_normal_card_everything_is_redealt() {
        List<PlayingCard> normal = Deck.all().stream().filter(PlayingCard::isNormal).toList();
        List<PlayingCard> other = Deck.all().stream().filter(card -> !card.isNormal()).toList();
        List<PlayingCard> bad = new ArrayList<>(normal);   // 28장 일반 카드가 전부 6인 손패(42장)로
        bad.addAll(other);
        AtomicInteger calls = new AtomicInteger();

        Dealer.Deal deal = Dealer.deal(6, cards -> calls.incrementAndGet() == 1 ? bad : Deck.all());

        assertThat(calls).hasValue(2);
        assertThat(deal.startCard().isNormal()).isTrue();
    }

    @Test
    void seat_counts_outside_2_to_6_are_rejected() {
        assertThatThrownBy(() -> Dealer.deal(1, Dealer.random(new Random(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dealer.deal(7, Dealer.random(new Random(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
