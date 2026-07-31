package com.mirboard.domain.game.skullking.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SkullCardTest {

    @Test
    void suit_card_carries_suit_and_rank() {
        SkullCard card = SkullCard.of(SkullSuit.GREEN, 7);

        assertThat(card.suit()).isEqualTo(SkullSuit.GREEN);
        assertThat(card.rank()).isEqualTo(7);
        assertThat(card.special()).isNull();
        assertThat(card.isSuit()).isTrue();
        assertThat(card.isSpecial()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SpecialKind.class)
    void special_card_carries_kind_only(SpecialKind kind) {
        SkullCard card = SkullCard.special(kind);

        assertThat(card.special()).isEqualTo(kind);
        assertThat(card.suit()).isNull();
        assertThat(card.rank()).isZero();
        assertThat(card.isSpecial()).isTrue();
        assertThat(card.isSuit()).isFalse();
        assertThat(card.is(kind)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {SkullSuit.MIN_RANK, 2, 13, SkullSuit.MAX_RANK})
    void rank_within_one_to_fourteen_is_accepted(int rank) {
        assertThat(SkullCard.of(SkullSuit.PURPLE, rank).rank()).isEqualTo(rank);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 15, 100})
    void rank_outside_one_to_fourteen_is_rejected(int rank) {
        assertThatThrownBy(() -> SkullCard.of(SkullSuit.PURPLE, rank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rank must be in");
    }

    @Test
    void special_card_with_a_suit_is_rejected() {
        assertThatThrownBy(() -> new SkullCard(SkullSuit.BLACK, 0, SpecialKind.PIRATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not have a suit");
    }

    @Test
    void special_card_with_a_nonzero_rank_is_rejected() {
        assertThatThrownBy(() -> new SkullCard(null, 5, SpecialKind.PIRATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have rank 0");
    }

    @Test
    void suit_card_without_a_suit_is_rejected() {
        assertThatThrownBy(() -> new SkullCard(null, 5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void only_black_is_trump() {
        assertThat(SkullCard.of(SkullSuit.BLACK, 1).isTrump()).isTrue();
        assertThat(SkullCard.of(SkullSuit.GREEN, 14).isTrump()).isFalse();
        assertThat(SkullCard.of(SkullSuit.PURPLE, 14).isTrump()).isFalse();
        assertThat(SkullCard.of(SkullSuit.YELLOW, 14).isTrump()).isFalse();
        assertThat(SkullCard.pirate().isTrump()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SkullSuit.class)
    void fourteen_of_any_suit_is_a_bonus_card(SkullSuit suit) {
        assertThat(SkullCard.of(suit, 14).isBonusFourteen()).isTrue();
        assertThat(SkullCard.of(suit, 13).isBonusFourteen()).isFalse();
    }

    @Test
    void special_cards_are_never_bonus_fourteen() {
        assertThat(SkullCard.skullKing().isBonusFourteen()).isFalse();
        assertThat(SkullCard.escape().isBonusFourteen()).isFalse();
    }

    /**
     * 중복 특수 카드는 값이 같다 — copy 인덱스를 두지 않는다는 D-101 판단의 회귀 가드.
     * 이 등가성이 깨지면 손패에서 카드를 제거하는 모든 경로가 조용히 바뀐다.
     */
    @Test
    void identical_special_cards_are_equal() {
        assertThat(SkullCard.pirate()).isEqualTo(SkullCard.pirate());
        assertThat(SkullCard.pirate()).hasSameHashCodeAs(SkullCard.pirate());
        assertThat(SkullCard.of(SkullSuit.GREEN, 3)).isEqualTo(SkullCard.of(SkullSuit.GREEN, 3));
        assertThat(SkullCard.of(SkullSuit.GREEN, 3)).isNotEqualTo(SkullCard.of(SkullSuit.BLACK, 3));
    }
}
