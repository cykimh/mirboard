package com.mirboard.domain.game.tichu.hand;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HandDetectorTest {

    // --- helpers ---
    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    @Nested
    class Single {
        @Test
        void normal_card_is_single() {
            assertThat(HandDetector.detect(List.of(n(Suit.JADE, 9))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.SINGLE);
                        assertThat(h.rank()).isEqualTo(9);
                    });
        }

        @Test
        void dragon_is_single() {
            assertThat(HandDetector.detect(List.of(Card.dragon())))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.SINGLE);
                        assertThat(h.rank()).isEqualTo(100);
                    });
        }

        @Test
        void dog_is_single() {
            assertThat(HandDetector.detect(List.of(Card.dog())))
                    .hasValueSatisfying(h -> assertThat(h.type()).isEqualTo(HandType.SINGLE));
        }

        @Test
        void mahjong_is_single() {
            assertThat(HandDetector.detect(List.of(Card.mahjong())))
                    .hasValueSatisfying(h -> assertThat(h.rank()).isEqualTo(1));
        }

        @Test
        void phoenix_alone_unsupported_in_phase_3b() {
            assertThat(HandDetector.detect(List.of(Card.phoenix()))).isEmpty();
        }

        @Test
        void empty_is_empty() {
            assertThat(HandDetector.detect(List.of())).isEmpty();
        }
    }

    @Nested
    class Pair {
        @Test
        void two_of_a_kind() {
            assertThat(HandDetector.detect(List.of(n(Suit.JADE, 7), n(Suit.SWORD, 7))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.PAIR);
                        assertThat(h.rank()).isEqualTo(7);
                    });
        }

        @Test
        void two_aces() {
            assertThat(HandDetector.detect(List.of(n(Suit.JADE, 14), n(Suit.STAR, 14))))
                    .hasValueSatisfying(h -> assertThat(h.rank()).isEqualTo(14));
        }

        @Test
        void two_twos() {
            assertThat(HandDetector.detect(List.of(n(Suit.PAGODA, 2), n(Suit.STAR, 2))))
                    .hasValueSatisfying(h -> assertThat(h.type()).isEqualTo(HandType.PAIR));
        }

        @Test
        void different_ranks_not_a_pair() {
            assertThat(HandDetector.detect(List.of(n(Suit.JADE, 7), n(Suit.SWORD, 8))))
                    .isEmpty();
        }

        @Test
        void mahjong_with_two_not_a_pair() {
            assertThat(HandDetector.detect(List.of(Card.mahjong(), n(Suit.JADE, 2))))
                    .isEmpty();
        }

        @Test
        void dragon_with_normal_not_a_pair() {
            assertThat(HandDetector.detect(List.of(Card.dragon(), n(Suit.JADE, 14))))
                    .isEmpty();
        }
    }

    @Nested
    class Triple {
        @Test
        void three_of_a_kind() {
            assertThat(HandDetector.detect(
                    List.of(n(Suit.JADE, 9), n(Suit.SWORD, 9), n(Suit.STAR, 9))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.TRIPLE);
                        assertThat(h.rank()).isEqualTo(9);
                    });
        }

        @Test
        void three_twos() {
            assertThat(HandDetector.detect(
                    List.of(n(Suit.JADE, 2), n(Suit.SWORD, 2), n(Suit.PAGODA, 2))))
                    .hasValueSatisfying(h -> assertThat(h.type()).isEqualTo(HandType.TRIPLE));
        }

        @Test
        void three_aces() {
            assertThat(HandDetector.detect(
                    List.of(n(Suit.JADE, 14), n(Suit.SWORD, 14), n(Suit.STAR, 14))))
                    .hasValueSatisfying(h -> assertThat(h.rank()).isEqualTo(14));
        }

        @Test
        void not_all_same_rank() {
            assertThat(HandDetector.detect(
                    List.of(n(Suit.JADE, 9), n(Suit.SWORD, 9), n(Suit.STAR, 10))))
                    .isEmpty();
        }

        @Test
        void with_mahjong_not_triple() {
            assertThat(HandDetector.detect(
                    List.of(Card.mahjong(), n(Suit.JADE, 1), n(Suit.SWORD, 1))))
                    .isEmpty();
        }

        @Test
        void with_dragon_not_triple() {
            assertThat(HandDetector.detect(
                    List.of(Card.dragon(), n(Suit.JADE, 14), n(Suit.SWORD, 14))))
                    .isEmpty();
        }
    }

    @Nested
    class FullHouse {
        @Test
        void three_plus_two() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 5), n(Suit.STAR, 5),
                    n(Suit.JADE, 9), n(Suit.PAGODA, 9))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.FULL_HOUSE);
                        assertThat(h.rank()).isEqualTo(5); // compared by triple
                    });
        }

        @Test
        void two_plus_three_order_irrelevant() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 3), n(Suit.SWORD, 3),
                    n(Suit.JADE, 11), n(Suit.SWORD, 11), n(Suit.STAR, 11))))
                    .hasValueSatisfying(h -> assertThat(h.rank()).isEqualTo(11));
        }

        @Test
        void aces_over_kings() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 14), n(Suit.SWORD, 14), n(Suit.STAR, 14),
                    n(Suit.JADE, 13), n(Suit.STAR, 13))))
                    .hasValueSatisfying(h -> assertThat(h.rank()).isEqualTo(14));
        }

        @Test
        void four_of_a_kind_plus_one_not_full_house() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 5), n(Suit.STAR, 5), n(Suit.PAGODA, 5),
                    n(Suit.JADE, 9))))
                    .isEmpty();
        }

        @Test
        void two_pair_plus_one_not_full_house() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 5),
                    n(Suit.JADE, 9), n(Suit.SWORD, 9),
                    n(Suit.STAR, 7))))
                    .isEmpty();
        }

        @Test
        void five_distinct_ranks_falls_through_to_straight_not_full_house() {
            // 5-6-7-8-9 of mixed suits is detected as STRAIGHT, never as FULL_HOUSE.
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 6),
                    n(Suit.JADE, 7), n(Suit.SWORD, 8),
                    n(Suit.STAR, 9))))
                    .hasValueSatisfying(h -> assertThat(h.type()).isEqualTo(HandType.STRAIGHT));
        }
    }

    @Nested
    class Straight {
        @Test
        void simple_five_card_straight() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 6), n(Suit.PAGODA, 7),
                    n(Suit.STAR, 8), n(Suit.JADE, 9))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.STRAIGHT);
                        assertThat(h.rank()).isEqualTo(9);
                        assertThat(h.length()).isEqualTo(5);
                    });
        }

        @Test
        void straight_starting_with_mahjong() {
            assertThat(HandDetector.detect(List.of(
                    Card.mahjong(),
                    n(Suit.JADE, 2), n(Suit.SWORD, 3),
                    n(Suit.PAGODA, 4), n(Suit.STAR, 5))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.STRAIGHT);
                        assertThat(h.rank()).isEqualTo(5);
                    });
        }

        @Test
        void long_straight() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 9), n(Suit.SWORD, 10), n(Suit.PAGODA, 11),
                    n(Suit.STAR, 12), n(Suit.JADE, 13), n(Suit.STAR, 14))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.STRAIGHT);
                        assertThat(h.rank()).isEqualTo(14);
                        assertThat(h.length()).isEqualTo(6);
                    });
        }

        @Test
        void four_card_run_not_straight() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 6),
                    n(Suit.PAGODA, 7), n(Suit.STAR, 8))))
                    .isEmpty();
        }

        @Test
        void gap_in_run_not_straight() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 6),
                    n(Suit.PAGODA, 8), n(Suit.STAR, 9), n(Suit.JADE, 10))))
                    .isEmpty();
        }

        @Test
        void straight_cannot_contain_dragon() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 10), n(Suit.SWORD, 11),
                    n(Suit.PAGODA, 12), n(Suit.STAR, 13), Card.dragon())))
                    .isEmpty();
        }
    }

    @Nested
    class ConsecutivePairs {
        @Test
        void three_consecutive_pairs() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 4), n(Suit.SWORD, 4),
                    n(Suit.JADE, 5), n(Suit.SWORD, 5),
                    n(Suit.JADE, 6), n(Suit.SWORD, 6))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.CONSECUTIVE_PAIRS);
                        assertThat(h.rank()).isEqualTo(6);
                        assertThat(h.length()).isEqualTo(6);
                    });
        }

        @Test
        void four_consecutive_pairs() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 10), n(Suit.STAR, 10),
                    n(Suit.JADE, 11), n(Suit.STAR, 11),
                    n(Suit.JADE, 12), n(Suit.STAR, 12),
                    n(Suit.JADE, 13), n(Suit.STAR, 13))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.CONSECUTIVE_PAIRS);
                        assertThat(h.length()).isEqualTo(8);
                    });
        }

        @Test
        void two_pairs_too_short() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 4), n(Suit.SWORD, 4),
                    n(Suit.JADE, 5), n(Suit.SWORD, 5))))
                    .isEmpty();
        }

        @Test
        void gap_between_pairs_invalid() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 4), n(Suit.SWORD, 4),
                    n(Suit.JADE, 5), n(Suit.SWORD, 5),
                    n(Suit.JADE, 7), n(Suit.SWORD, 7))))
                    .isEmpty();
        }

        @Test
        void six_cards_with_a_triple_invalid() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 4), n(Suit.SWORD, 4),
                    n(Suit.JADE, 5), n(Suit.SWORD, 5), n(Suit.STAR, 5),
                    n(Suit.JADE, 6))))
                    .isEmpty();
        }
    }

    @Nested
    class Bomb {
        @Test
        void four_of_a_kind() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 8), n(Suit.SWORD, 8),
                    n(Suit.PAGODA, 8), n(Suit.STAR, 8))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.BOMB);
                        assertThat(h.rank()).isEqualTo(8);
                    });
        }

        @Test
        void four_aces_bomb() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 14), n(Suit.SWORD, 14),
                    n(Suit.PAGODA, 14), n(Suit.STAR, 14))))
                    .hasValueSatisfying(h -> assertThat(h.rank()).isEqualTo(14));
        }

        @Test
        void four_twos_bomb() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 2), n(Suit.SWORD, 2),
                    n(Suit.PAGODA, 2), n(Suit.STAR, 2))))
                    .hasValueSatisfying(h -> assertThat(h.rank()).isEqualTo(2));
        }

        @Test
        void three_plus_one_not_bomb() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 5), n(Suit.SWORD, 5),
                    n(Suit.PAGODA, 5), n(Suit.STAR, 6))))
                    .isEmpty();
        }
    }

    @Nested
    class StraightFlushBomb {
        @Test
        void five_card_same_suit_consecutive() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 4), n(Suit.JADE, 5), n(Suit.JADE, 6),
                    n(Suit.JADE, 7), n(Suit.JADE, 8))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.STRAIGHT_FLUSH_BOMB);
                        assertThat(h.rank()).isEqualTo(8);
                        assertThat(h.length()).isEqualTo(5);
                    });
        }

        @Test
        void longer_sfb() {
            assertThat(HandDetector.detect(List.of(
                    n(Suit.STAR, 9), n(Suit.STAR, 10), n(Suit.STAR, 11),
                    n(Suit.STAR, 12), n(Suit.STAR, 13), n(Suit.STAR, 14))))
                    .hasValueSatisfying(h -> {
                        assertThat(h.type()).isEqualTo(HandType.STRAIGHT_FLUSH_BOMB);
                        assertThat(h.length()).isEqualTo(6);
                    });
        }

        @Test
        void mixed_suits_not_sfb() {
            // Should detect plain STRAIGHT instead.
            assertThat(HandDetector.detect(List.of(
                    n(Suit.JADE, 4), n(Suit.SWORD, 5), n(Suit.JADE, 6),
                    n(Suit.STAR, 7), n(Suit.JADE, 8))))
                    .hasValueSatisfying(h -> assertThat(h.type()).isEqualTo(HandType.STRAIGHT));
        }

        @Test
        void mahjong_disallowed_in_sfb() {
            // mahjong has no suit → only STRAIGHT, not SFB
            assertThat(HandDetector.detect(List.of(
                    Card.mahjong(),
                    n(Suit.JADE, 2), n(Suit.JADE, 3), n(Suit.JADE, 4), n(Suit.JADE, 5))))
                    .hasValueSatisfying(h -> assertThat(h.type()).isEqualTo(HandType.STRAIGHT));
        }
    }
}
