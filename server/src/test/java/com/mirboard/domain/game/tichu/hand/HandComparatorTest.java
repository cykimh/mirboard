package com.mirboard.domain.game.tichu.hand;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import java.util.List;
import org.junit.jupiter.api.Test;

class HandComparatorTest {

    private static Hand detect(Card... cards) {
        return HandDetector.detect(List.of(cards))
                .orElseThrow(() -> new IllegalStateException("Could not detect hand"));
    }

    @Test
    void higher_single_beats_lower() {
        var lower = detect(Card.normal(Suit.JADE, 5));
        var higher = detect(Card.normal(Suit.SWORD, 9));

        assertThat(HandComparator.canBeat(higher, lower)).isTrue();
        assertThat(HandComparator.canBeat(lower, higher)).isFalse();
    }

    @Test
    void dragon_beats_any_single() {
        var ace = detect(Card.normal(Suit.JADE, 14));
        var dragon = detect(Card.dragon());

        assertThat(HandComparator.canBeat(dragon, ace)).isTrue();
        assertThat(HandComparator.canBeat(ace, dragon)).isFalse();
    }

    @Test
    void equal_rank_cannot_beat() {
        var fiveJade = detect(Card.normal(Suit.JADE, 5));
        var fiveSword = detect(Card.normal(Suit.SWORD, 5));

        assertThat(HandComparator.canBeat(fiveJade, fiveSword)).isFalse();
        assertThat(HandComparator.canBeat(fiveSword, fiveJade)).isFalse();
    }

    @Test
    void different_types_cannot_beat_except_bombs() {
        var pair = detect(Card.normal(Suit.JADE, 9), Card.normal(Suit.SWORD, 9));
        var triple = detect(
                Card.normal(Suit.JADE, 5), Card.normal(Suit.SWORD, 5), Card.normal(Suit.STAR, 5));

        assertThat(HandComparator.canBeat(triple, pair)).isFalse();
        assertThat(HandComparator.canBeat(pair, triple)).isFalse();
    }

    @Test
    void same_type_different_length_cannot_beat() {
        var shortStraight = detect(
                Card.normal(Suit.JADE, 5), Card.normal(Suit.SWORD, 6),
                Card.normal(Suit.JADE, 7), Card.normal(Suit.SWORD, 8),
                Card.normal(Suit.JADE, 9));
        var longStraight = detect(
                Card.normal(Suit.JADE, 5), Card.normal(Suit.SWORD, 6),
                Card.normal(Suit.JADE, 7), Card.normal(Suit.SWORD, 8),
                Card.normal(Suit.JADE, 9), Card.normal(Suit.SWORD, 10));

        assertThat(HandComparator.canBeat(longStraight, shortStraight)).isFalse();
        assertThat(HandComparator.canBeat(shortStraight, longStraight)).isFalse();
    }

    @Test
    void bomb_beats_non_bomb() {
        var triple = detect(
                Card.normal(Suit.JADE, 14), Card.normal(Suit.SWORD, 14),
                Card.normal(Suit.STAR, 14));
        var bombTwos = detect(
                Card.normal(Suit.JADE, 2), Card.normal(Suit.SWORD, 2),
                Card.normal(Suit.PAGODA, 2), Card.normal(Suit.STAR, 2));

        assertThat(HandComparator.canBeat(bombTwos, triple)).isTrue();
        assertThat(HandComparator.canBeat(triple, bombTwos)).isFalse();
    }

    @Test
    void higher_bomb_beats_lower_bomb() {
        var bombSeven = detect(
                Card.normal(Suit.JADE, 7), Card.normal(Suit.SWORD, 7),
                Card.normal(Suit.PAGODA, 7), Card.normal(Suit.STAR, 7));
        var bombAce = detect(
                Card.normal(Suit.JADE, 14), Card.normal(Suit.SWORD, 14),
                Card.normal(Suit.PAGODA, 14), Card.normal(Suit.STAR, 14));

        assertThat(HandComparator.canBeat(bombAce, bombSeven)).isTrue();
        assertThat(HandComparator.canBeat(bombSeven, bombAce)).isFalse();
    }

    @Test
    void straight_flush_bomb_beats_regular_bomb() {
        var bombAce = detect(
                Card.normal(Suit.JADE, 14), Card.normal(Suit.SWORD, 14),
                Card.normal(Suit.PAGODA, 14), Card.normal(Suit.STAR, 14));
        var sfb = detect(
                Card.normal(Suit.JADE, 3), Card.normal(Suit.JADE, 4),
                Card.normal(Suit.JADE, 5), Card.normal(Suit.JADE, 6),
                Card.normal(Suit.JADE, 7));

        assertThat(HandComparator.canBeat(sfb, bombAce)).isTrue();
        assertThat(HandComparator.canBeat(bombAce, sfb)).isFalse();
    }

    @Test
    void longer_sfb_beats_shorter_sfb_regardless_of_rank() {
        var shortSfbHigh = detect(
                Card.normal(Suit.JADE, 10), Card.normal(Suit.JADE, 11),
                Card.normal(Suit.JADE, 12), Card.normal(Suit.JADE, 13),
                Card.normal(Suit.JADE, 14));
        var longSfbLow = detect(
                Card.normal(Suit.SWORD, 2), Card.normal(Suit.SWORD, 3),
                Card.normal(Suit.SWORD, 4), Card.normal(Suit.SWORD, 5),
                Card.normal(Suit.SWORD, 6), Card.normal(Suit.SWORD, 7));

        assertThat(HandComparator.canBeat(longSfbLow, shortSfbHigh)).isTrue();
        assertThat(HandComparator.canBeat(shortSfbHigh, longSfbLow)).isFalse();
    }

    @Test
    void same_length_sfb_compares_by_rank() {
        var sfbLow = detect(
                Card.normal(Suit.JADE, 3), Card.normal(Suit.JADE, 4),
                Card.normal(Suit.JADE, 5), Card.normal(Suit.JADE, 6),
                Card.normal(Suit.JADE, 7));
        var sfbHigh = detect(
                Card.normal(Suit.STAR, 8), Card.normal(Suit.STAR, 9),
                Card.normal(Suit.STAR, 10), Card.normal(Suit.STAR, 11),
                Card.normal(Suit.STAR, 12));

        assertThat(HandComparator.canBeat(sfbHigh, sfbLow)).isTrue();
        assertThat(HandComparator.canBeat(sfbLow, sfbHigh)).isFalse();
    }

    @Test
    void null_inputs_return_false() {
        var ace = detect(Card.normal(Suit.JADE, 14));
        assertThat(HandComparator.canBeat(null, ace)).isFalse();
        assertThat(HandComparator.canBeat(ace, null)).isFalse();
    }
}
