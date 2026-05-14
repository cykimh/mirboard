package com.mirboard.domain.game.tichu.hand;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhoenixDetectionTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    @Test
    void phoenix_alone_is_phoenix_single() {
        var hand = HandDetector.detect(List.of(Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.SINGLE);
        assertThat(hand.phoenixSingle()).isTrue();
    }

    @Test
    void phoenix_plus_normal_is_pair() {
        var hand = HandDetector.detect(List.of(n(Suit.JADE, 9), Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.PAIR);
        assertThat(hand.rank()).isEqualTo(9);
    }

    @Test
    void phoenix_plus_pair_is_triple() {
        var hand = HandDetector.detect(List.of(
                n(Suit.JADE, 7), n(Suit.SWORD, 7), Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.TRIPLE);
        assertThat(hand.rank()).isEqualTo(7);
    }

    @Test
    void phoenix_fills_full_house_from_triple_plus_one() {
        // 5-5-5 + 9 + Phoenix → Phoenix becomes 9 → full house, triple = 5
        var hand = HandDetector.detect(List.of(
                n(Suit.JADE, 5), n(Suit.SWORD, 5), n(Suit.STAR, 5),
                n(Suit.JADE, 9), Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.FULL_HOUSE);
        assertThat(hand.rank()).isEqualTo(5);
    }

    @Test
    void phoenix_promotes_two_pairs_to_full_house_picking_higher_triple() {
        // 5-5 + 9-9 + Phoenix → Phoenix joins 9-9 → triple 9, pair 5 (rank=9 > rank=5)
        var hand = HandDetector.detect(List.of(
                n(Suit.JADE, 5), n(Suit.SWORD, 5),
                n(Suit.JADE, 9), n(Suit.SWORD, 9),
                Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.FULL_HOUSE);
        assertThat(hand.rank()).isEqualTo(9);
    }

    @Test
    void phoenix_fills_straight_gap() {
        // 3-4-6-7 + Phoenix → Phoenix=5 → straight 3..7
        var hand = HandDetector.detect(List.of(
                n(Suit.JADE, 3), n(Suit.SWORD, 4),
                n(Suit.PAGODA, 6), n(Suit.STAR, 7),
                Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.STRAIGHT);
        assertThat(hand.rank()).isEqualTo(7);
    }

    @Test
    void phoenix_extends_straight_to_higher_top() {
        // 5-6-7-8 + Phoenix → choose Phoenix=9 (top=9) over Phoenix=4 (top=8)
        var hand = HandDetector.detect(List.of(
                n(Suit.JADE, 5), n(Suit.SWORD, 6),
                n(Suit.PAGODA, 7), n(Suit.STAR, 8),
                Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.STRAIGHT);
        assertThat(hand.rank()).isEqualTo(9);
    }

    @Test
    void phoenix_completes_consecutive_pairs() {
        // 3-3 + 4-4 + 5 + Phoenix → Phoenix=5 → 3-3-4-4-5-5
        var hand = HandDetector.detect(List.of(
                n(Suit.JADE, 3), n(Suit.SWORD, 3),
                n(Suit.JADE, 4), n(Suit.SWORD, 4),
                n(Suit.JADE, 5), Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.CONSECUTIVE_PAIRS);
        assertThat(hand.rank()).isEqualTo(5);
        assertThat(hand.length()).isEqualTo(6);
    }

    @Test
    void phoenix_in_four_of_kind_returns_empty_no_bomb_allowed() {
        // 4 cards: 3-3-3 + Phoenix. No non-bomb 4-card hand exists. Should return empty.
        assertThat(HandDetector.detect(List.of(
                n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.STAR, 3),
                Card.phoenix())))
                .isEmpty();
    }

    @Test
    void phoenix_cannot_make_straight_flush_bomb() {
        // 4 same-suit + Phoenix. Phoenix has no suit → SFB impossible. STRAIGHT detected instead.
        var hand = HandDetector.detect(List.of(
                n(Suit.JADE, 4), n(Suit.JADE, 5), n(Suit.JADE, 6),
                n(Suit.JADE, 7), Card.phoenix())).orElseThrow();
        assertThat(hand.type()).isEqualTo(HandType.STRAIGHT);
    }

    @Test
    void phoenix_with_dragon_invalid_combo() {
        assertThat(HandDetector.detect(List.of(Card.dragon(), Card.phoenix())))
                .isEmpty();
    }

    @Test
    void phoenix_with_dog_invalid_combo() {
        assertThat(HandDetector.detect(List.of(Card.dog(), Card.phoenix())))
                .isEmpty();
    }
}
