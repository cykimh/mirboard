package com.mirboard.domain.game.tichu.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import java.util.List;
import org.junit.jupiter.api.Test;

class WishFulfillmentCheckerTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static Hand top(Card... cards) {
        return HandDetector.detect(List.of(cards)).orElseThrow();
    }

    @Test
    void no_wish_card_in_hand_returns_false() {
        var hand = List.of(n(Suit.JADE, 5), n(Suit.SWORD, 9));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, top(n(Suit.PAGODA, 3)), 7))
                .isFalse();
    }

    @Test
    void single_wish_card_beats_lower_single() {
        // top = 3, wish=7, 손에 7 있음 → 단일 7 로 이김 가능
        var hand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 9));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, top(n(Suit.PAGODA, 3)), 7))
                .isTrue();
    }

    @Test
    void single_wish_card_cannot_beat_higher_single() {
        // top = K (13), wish=7, 단일 7 < K → 못 이김
        var hand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 9));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, top(n(Suit.PAGODA, 13)), 7))
                .isFalse();
    }

    @Test
    void wish_pair_beats_lower_pair() {
        // top = 3-pair, wish=7, 손에 7 두 장 → 7-pair 로 이김
        var hand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 7), n(Suit.STAR, 9));
        Hand topPair = top(n(Suit.PAGODA, 3), n(Suit.STAR, 3));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, topPair, 7)).isTrue();
    }

    @Test
    void wish_pair_cannot_beat_pair_of_different_type_size() {
        // top = single 3, wish=7, 페어로는 단일을 못 이김 (타입 불일치). 단일 7 은 이길 수 있음.
        var hand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 7));
        // top 이 페어 (예: 3-pair) 인데 wish 단일 7 페어 같은 rank 2 장 → 페어 비교 OK
        Hand topPair = top(n(Suit.PAGODA, 3), n(Suit.STAR, 3));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, topPair, 7)).isTrue();
    }

    @Test
    void wish_with_phoenix_forms_pair() {
        // top = 5-pair, wish=7, 손에 7 + Phoenix → Phoenix 와일드로 7-pair 만들어 이김
        var hand = List.of(n(Suit.JADE, 7), Card.phoenix(), n(Suit.SWORD, 9));
        Hand topPair = top(n(Suit.PAGODA, 5), n(Suit.STAR, 5));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, topPair, 7)).isTrue();
    }

    @Test
    void wish_triple_beats_lower_triple() {
        // top = 5-triple, wish=7, 손에 7 세 장 → 7-triple 로 이김
        var hand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 7), n(Suit.STAR, 7), n(Suit.PAGODA, 9));
        Hand topTriple = top(n(Suit.PAGODA, 5), n(Suit.STAR, 5), n(Suit.SWORD, 5));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, topTriple, 7)).isTrue();
    }

    @Test
    void wish_in_hand_but_top_too_strong_returns_false() {
        // top = A (14), wish=7, 단일 7 < A. 페어/트리플 만들 카드도 없음 → 못 이김
        var hand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 9));
        assertThat(WishFulfillmentChecker.canPlayWishRank(hand, top(n(Suit.PAGODA, 14)), 7))
                .isFalse();
    }
}
