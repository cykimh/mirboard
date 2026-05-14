package com.mirboard.domain.game.tichu.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DeckTest {

    @Test
    void unshuffled_deck_contains_all_56_unique_cards() {
        List<Card> cards = Deck.unshuffled().cards();

        assertThat(cards).hasSize(56);
        assertThat(Set.copyOf(cards))
                .as("Every card must be unique")
                .hasSize(56);
    }

    @Test
    void unshuffled_deck_has_full_suit_rank_grid() {
        List<Card> cards = Deck.unshuffled().cards();

        for (Suit suit : Suit.values()) {
            for (int rank = 2; rank <= 14; rank++) {
                Card expected = Card.normal(suit, rank);
                assertThat(cards).contains(expected);
            }
        }
    }

    @Test
    void unshuffled_deck_contains_all_four_specials() {
        Set<Special> specials = Deck.unshuffled().cards().stream()
                .map(Card::special)
                .filter(s -> s != null)
                .collect(Collectors.toSet());

        assertThat(specials).containsExactlyInAnyOrder(
                Special.MAHJONG, Special.DOG, Special.PHOENIX, Special.DRAGON);
    }

    @Test
    void shuffled_with_seed_is_deterministic() {
        Deck a = Deck.shuffled(new Random(42L));
        Deck b = Deck.shuffled(new Random(42L));

        assertThat(a.cards()).isEqualTo(b.cards());
    }

    @Test
    void shuffled_preserves_card_set() {
        Deck shuffled = Deck.shuffled(new Random(1L));

        assertThat(shuffled.cards()).hasSize(56);
        assertThat(new HashSet<>(shuffled.cards()))
                .isEqualTo(new HashSet<>(Deck.unshuffled().cards()));
    }

    @Test
    void shuffled_actually_reorders_with_high_probability() {
        Deck shuffled = Deck.shuffled(new Random(7L));

        // With 56 cards a shuffle producing the natural order has probability 1/56! ≈ 0.
        assertThat(shuffled.cards()).isNotEqualTo(Deck.unshuffled().cards());
    }
}
