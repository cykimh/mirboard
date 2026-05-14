package com.mirboard.domain.game.tichu.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CardTest {

    @Test
    void normal_card_rejects_invalid_rank() {
        assertThatThrownBy(() -> Card.normal(Suit.JADE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Card.normal(Suit.JADE, 15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normal_card_rejects_null_suit() {
        assertThatThrownBy(() -> new Card(null, 5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void special_card_must_not_have_suit() {
        assertThatThrownBy(() -> new Card(Suit.JADE, 0, Special.PHOENIX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void point_values_match_tichu_rulebook() {
        assertThat(Card.normal(Suit.JADE, 5).points()).isEqualTo(5);
        assertThat(Card.normal(Suit.JADE, 10).points()).isEqualTo(10);
        assertThat(Card.normal(Suit.JADE, 13).points()).isEqualTo(10); // King
        assertThat(Card.normal(Suit.JADE, 14).points()).isZero();      // Ace
        assertThat(Card.normal(Suit.JADE, 2).points()).isZero();
        assertThat(Card.dragon().points()).isEqualTo(25);
        assertThat(Card.phoenix().points()).isEqualTo(-25);
        assertThat(Card.mahjong().points()).isZero();
        assertThat(Card.dog().points()).isZero();
    }

    @Test
    void special_factories_set_canonical_ranks() {
        assertThat(Card.dog().rank()).isZero();
        assertThat(Card.mahjong().rank()).isEqualTo(1);
        assertThat(Card.phoenix().rank()).isZero();
        assertThat(Card.dragon().rank()).isEqualTo(100);
    }

    @Test
    void is_helpers_classify_correctly() {
        var nine = Card.normal(Suit.SWORD, 9);
        var phoenix = Card.phoenix();

        assertThat(nine.isNormal()).isTrue();
        assertThat(nine.isSpecial()).isFalse();
        assertThat(phoenix.isSpecial()).isTrue();
        assertThat(phoenix.is(Special.PHOENIX)).isTrue();
        assertThat(phoenix.is(Special.DRAGON)).isFalse();
    }
}
