package com.mirboard.domain.game.onecard.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §1 — 카드와 역할. */
class PlayingCardTest {

    private static final PlayingCard BLACK = PlayingCard.joker(Joker.BLACK);
    private static final PlayingCard COLOR = PlayingCard.joker(Joker.COLOR);

    @Test
    void the_deck_has_54_distinct_cards() {
        assertThat(Deck.all()).hasSize(Deck.SIZE).doesNotHaveDuplicates();
        assertThat(Deck.all()).filteredOn(PlayingCard::isJoker).containsExactly(BLACK, COLOR);
    }

    @Test
    void attack_values_follow_section_1() {
        assertThat(PlayingCard.of(Suit.SPADE, 2).attackValue()).isEqualTo(2);
        assertThat(PlayingCard.of(Suit.SPADE, PlayingCard.ACE).attackValue()).isEqualTo(3);
        assertThat(BLACK.attackValue()).isEqualTo(5);
        assertThat(COLOR.attackValue()).isEqualTo(7);
        assertThat(PlayingCard.of(Suit.SPADE, 5).attackValue()).isZero();
        assertThat(PlayingCard.of(Suit.SPADE, 5).isAttack()).isFalse();
    }

    @Test
    void attack_strength_orders_two_ace_black_color() {
        assertThat(PlayingCard.of(Suit.HEART, 2).attackStrength()).isEqualTo(1);
        assertThat(PlayingCard.of(Suit.HEART, PlayingCard.ACE).attackStrength()).isEqualTo(2);
        assertThat(BLACK.attackStrength()).isEqualTo(3);
        assertThat(COLOR.attackStrength()).isEqualTo(4);
        assertThat(PlayingCard.of(Suit.HEART, PlayingCard.KING).attackStrength()).isZero();
    }

    @Test
    void special_roles_belong_to_j_q_k_and_7_only() {
        assertThat(PlayingCard.of(Suit.CLUB, PlayingCard.JACK).isSkip()).isTrue();
        assertThat(PlayingCard.of(Suit.CLUB, PlayingCard.QUEEN).isReverse()).isTrue();
        assertThat(PlayingCard.of(Suit.CLUB, PlayingCard.KING).isExtraTurn()).isTrue();
        assertThat(PlayingCard.of(Suit.CLUB, 7).isSuitChange()).isTrue();
        assertThat(BLACK.isSkip() || BLACK.isReverse() || BLACK.isExtraTurn() || BLACK.isSuitChange()).isFalse();
    }

    @Test
    void normal_cards_are_3_to_10_without_7() {
        assertThat(Deck.all()).filteredOn(PlayingCard::isNormal).hasSize(28);
        assertThat(PlayingCard.of(Suit.DIAMOND, 7).isNormal()).isFalse();
        assertThat(PlayingCard.of(Suit.DIAMOND, 2).isNormal()).isFalse();
        assertThat(BLACK.isNormal()).isFalse();
    }

    @Test
    void malformed_cards_are_rejected() {
        assertThatThrownBy(() -> PlayingCard.of(Suit.SPADE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlayingCard.of(Suit.SPADE, 14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayingCard(Suit.SPADE, 0, Joker.BLACK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
