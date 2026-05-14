package com.mirboard.domain.game.tichu.hand;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhoenixComparatorTest {

    private static Hand detect(Card... cards) {
        return HandDetector.detect(List.of(cards)).orElseThrow();
    }

    @Test
    void phoenix_beats_normal_single() {
        var seven = detect(Card.normal(Suit.JADE, 7));
        var phoenix = detect(Card.phoenix());

        assertThat(HandComparator.canBeat(phoenix, seven)).isTrue();
    }

    @Test
    void phoenix_beats_ace() {
        var ace = detect(Card.normal(Suit.JADE, 14));
        var phoenix = detect(Card.phoenix());

        assertThat(HandComparator.canBeat(phoenix, ace)).isTrue();
    }

    @Test
    void phoenix_beats_mahjong() {
        var mahjong = detect(Card.mahjong());
        var phoenix = detect(Card.phoenix());

        assertThat(HandComparator.canBeat(phoenix, mahjong)).isTrue();
    }

    @Test
    void phoenix_cannot_beat_dragon() {
        var dragon = detect(Card.dragon());
        var phoenix = detect(Card.phoenix());

        assertThat(HandComparator.canBeat(phoenix, dragon)).isFalse();
    }

    @Test
    void normal_can_still_be_beaten_after_phoenix_resolved_to_rank() {
        // Engine resolves Phoenix-on-top-of-7 to a normal Hand(SINGLE, [Phoenix], rank=7).
        // Subsequent challenger of rank 8 should beat it; rank 7 should not.
        Hand phoenixResolvedAt7 = new Hand(HandType.SINGLE, List.of(Card.phoenix()), 7, 1);
        Hand eight = detect(Card.normal(Suit.JADE, 8));
        Hand sevenOtherSuit = detect(Card.normal(Suit.STAR, 7));

        assertThat(HandComparator.canBeat(eight, phoenixResolvedAt7)).isTrue();
        assertThat(HandComparator.canBeat(sevenOtherSuit, phoenixResolvedAt7)).isFalse();
    }

    @Test
    void phoenix_pair_compares_by_pair_rank() {
        var lowerPair = detect(Card.normal(Suit.JADE, 5), Card.phoenix());   // pair of 5
        var higherPair = detect(Card.normal(Suit.JADE, 9), Card.phoenix());   // pair of 9 (different game state, but for comparison)
        // Only one Phoenix in the deck — these can't co-exist in real play, but the
        // comparator must still order them by rank.

        assertThat(HandComparator.canBeat(higherPair, lowerPair)).isTrue();
        assertThat(HandComparator.canBeat(lowerPair, higherPair)).isFalse();
    }

    @Test
    void phoenix_cannot_beat_non_single() {
        var phoenix = detect(Card.phoenix());
        var pair = detect(Card.normal(Suit.JADE, 9), Card.normal(Suit.SWORD, 9));

        assertThat(HandComparator.canBeat(phoenix, pair)).isFalse();
    }

    @Test
    void phoenix_cannot_beat_bomb() {
        var phoenix = detect(Card.phoenix());
        // A bomb wouldn't normally be played as a SINGLE follow, but check defensively.
        Hand bomb = new Hand(HandType.BOMB, List.of(
                Card.normal(Suit.JADE, 5), Card.normal(Suit.SWORD, 5),
                Card.normal(Suit.PAGODA, 5), Card.normal(Suit.STAR, 5)), 5, 4);

        assertThat(HandComparator.canBeat(phoenix, bomb)).isFalse();
    }
}
