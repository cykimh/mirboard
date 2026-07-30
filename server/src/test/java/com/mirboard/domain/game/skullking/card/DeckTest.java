package com.mirboard.domain.game.skullking.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeckTest {

    @Test
    void deck_has_seventy_cards() {
        assertThat(Deck.unshuffled().cards()).hasSize(70);
        assertThat(Deck.SIZE).isEqualTo(70);
    }

    @Test
    void deck_is_fifty_six_suit_cards_plus_fourteen_specials() {
        List<SkullCard> cards = Deck.unshuffled().cards();

        assertThat(cards.stream().filter(SkullCard::isSuit)).hasSize(Deck.SUIT_CARD_COUNT);
        assertThat(cards.stream().filter(SkullCard::isSpecial)).hasSize(14);
        assertThat(Deck.SUIT_CARD_COUNT + 14).isEqualTo(Deck.SIZE);
    }

    @ParameterizedTest
    @EnumSource(SkullSuit.class)
    void every_suit_has_ranks_one_through_fourteen(SkullSuit suit) {
        List<SkullCard> cards = Deck.unshuffled().cards();

        for (int rank = 1; rank <= 14; rank++) {
            assertThat(cards)
                    .as("%s %d must be in the deck exactly once", suit, rank)
                    .containsOnlyOnce(SkullCard.of(suit, rank));
        }
        assertThat(cards.stream().filter(c -> c.suit() == suit)).hasSize(14);
    }

    @ParameterizedTest
    @EnumSource(SpecialKind.class)
    void special_card_counts_match_the_declared_deck_count(SpecialKind kind) {
        long actual = Deck.unshuffled().cards().stream().filter(c -> c.is(kind)).count();

        assertThat(actual)
                .as("%s count in deck", kind)
                .isEqualTo(kind.countInDeck());
    }

    /** §1 표를 그대로 옮긴 회귀 가드 — 장수가 바뀌면 §4 분배표가 통째로 흔들린다. */
    @Test
    void special_card_distribution_matches_the_spec_table() {
        assertThat(SpecialKind.PIRATE.countInDeck()).isEqualTo(5);
        assertThat(SpecialKind.MERMAID.countInDeck()).isEqualTo(2);
        assertThat(SpecialKind.SKULL_KING.countInDeck()).isEqualTo(1);
        assertThat(SpecialKind.TIGRESS.countInDeck()).isEqualTo(1);
        assertThat(SpecialKind.ESCAPE.countInDeck()).isEqualTo(5);
    }

    @Test
    void suit_cards_are_all_distinct_but_specials_repeat() {
        List<SkullCard> cards = Deck.unshuffled().cards();

        Set<SkullCard> distinctSuitCards =
                cards.stream().filter(SkullCard::isSuit).collect(Collectors.toSet());
        assertThat(distinctSuitCards)
                .as("색상 카드는 덱에 1장씩 — 이 성질이 카드 보존 불변식의 근거다")
                .hasSize(Deck.SUIT_CARD_COUNT);

        Set<SkullCard> distinctSpecials =
                cards.stream().filter(SkullCard::isSpecial).collect(Collectors.toSet());
        assertThat(distinctSpecials)
                .as("특수 카드는 값이 같은 것이 여러 장 (D-101: copy 인덱스 없음)")
                .hasSize(SpecialKind.values().length);
    }

    @Test
    void shuffle_preserves_the_multiset() {
        List<SkullCard> shuffled = Deck.shuffled(new Random(42)).cards();

        assertThat(shuffled).hasSize(Deck.SIZE);
        assertThat(frequency(shuffled)).isEqualTo(frequency(Deck.unshuffled().cards()));
    }

    @Test
    void shuffle_with_the_same_seed_is_deterministic() {
        assertThat(Deck.shuffled(new Random(7)).cards())
                .isEqualTo(Deck.shuffled(new Random(7)).cards());
    }

    @Test
    void shuffle_with_different_seeds_reorders() {
        assertThat(Deck.shuffled(new Random(1)).cards())
                .isNotEqualTo(Deck.shuffled(new Random(2)).cards());
    }

    @Test
    void deck_cards_cannot_be_mutated_by_callers() {
        List<SkullCard> cards = Deck.unshuffled().cards();

        assertThatThrownBy(() -> cards.add(SkullCard.pirate()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Deck.unshuffled().cards()).hasSize(Deck.SIZE);
    }

    private static java.util.Map<SkullCard, Long> frequency(List<SkullCard> cards) {
        return cards.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
