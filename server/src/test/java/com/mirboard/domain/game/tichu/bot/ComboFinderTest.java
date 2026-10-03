package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.hand.HandType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-118 — {@link ComboFinder} 는 봇이 고를 카드 묶음 후보를 만든다. 합법성 판정은 여전히
 * {@code ActionValidator} 몫이므로 여기서는 "필요한 조합이 빠지지 않는가"와 "족보 판정이
 * 의도와 같은가", "입력 순서와 무관한가"를 고정한다.
 */
class ComboFinderTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static List<Card> hand(Card... cards) {
        return List.of(cards);
    }

    private static Hand detect(List<Card> cards) {
        return HandDetector.detect(cards).orElseThrow(
                () -> new AssertionError("not a hand: " + cards));
    }

    private static List<Hand> detectAll(List<List<Card>> combos) {
        return combos.stream().map(ComboFinderTest::detect).toList();
    }

    private static boolean has(List<List<Card>> combos, HandType type, int rank, int length) {
        return detectAll(combos).stream()
                .anyMatch(h -> h.type() == type && h.rank() == rank && h.length() == length);
    }

    @Test
    void lead_finds_mahjong_straight_and_phoenix_gap_straight() {
        var cards = hand(Card.mahjong(), n(Suit.JADE, 2), n(Suit.SWORD, 3), n(Suit.STAR, 4),
                n(Suit.PAGODA, 5), n(Suit.JADE, 7), n(Suit.SWORD, 8), Card.phoenix());

        var combos = ComboFinder.lead(cards);

        // 마작(1)~5 스트레이트.
        assertThat(combos).anySatisfy(c -> assertThat(c)
                .containsExactly(Card.mahjong(), n(Suit.JADE, 2), n(Suit.SWORD, 3),
                        n(Suit.STAR, 4), n(Suit.PAGODA, 5)));
        // 봉황이 6 을 메운 4-8 스트레이트.
        assertThat(has(combos, HandType.STRAIGHT, 8, 5)).isTrue();
        // 봉황으로 늘린 1-8 (8장) 스트레이트.
        assertThat(has(combos, HandType.STRAIGHT, 8, 8)).isTrue();
    }

    @Test
    void lead_finds_phoenix_consecutive_pairs_and_full_houses() {
        var cards = hand(n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.JADE, 4),
                n(Suit.JADE, 9), n(Suit.SWORD, 9), n(Suit.STAR, 9), Card.phoenix());

        var combos = ComboFinder.lead(cards);

        // 3-3-4-P → 연속페어 3344.
        assertThat(has(combos, HandType.CONSECUTIVE_PAIRS, 4, 4)).isTrue();
        // 9-9-9 + 3-3 → 풀하우스.
        assertThat(has(combos, HandType.FULL_HOUSE, 9, 5)).isTrue();
        // 9-9-9 + 4-P → 봉황으로 짝을 채운 풀하우스.
        assertThat(combos).anySatisfy(c -> assertThat(c)
                .containsExactlyInAnyOrder(n(Suit.JADE, 4), n(Suit.JADE, 9), n(Suit.SWORD, 9),
                        n(Suit.STAR, 9), Card.phoenix()));
        // 봉황 페어·트리플.
        assertThat(has(combos, HandType.PAIR, 4, 2)).isTrue();
        assertThat(has(combos, HandType.TRIPLE, 3, 3)).isTrue();
    }

    @Test
    void lead_finds_four_of_a_kind_and_straight_flush_bombs() {
        var cards = hand(n(Suit.JADE, 6), n(Suit.SWORD, 6), n(Suit.STAR, 6), n(Suit.PAGODA, 6),
                n(Suit.STAR, 9), n(Suit.STAR, 10), n(Suit.STAR, 11), n(Suit.STAR, 12),
                n(Suit.STAR, 13), n(Suit.STAR, 14));

        var combos = ComboFinder.lead(cards);

        assertThat(has(combos, HandType.BOMB, 6, 4)).isTrue();
        assertThat(has(combos, HandType.STRAIGHT_FLUSH_BOMB, 13, 5)).isTrue();
        assertThat(has(combos, HandType.STRAIGHT_FLUSH_BOMB, 14, 6)).isTrue();
        assertThat(has(combos, HandType.STRAIGHT_FLUSH_BOMB, 14, 5)).isTrue();
    }

    @Test
    void plain_straight_avoids_same_suit_representatives_or_appears_only_as_bomb() {
        // 3~7 은 전부 JADE 지만 5 에 SWORD 가 하나 더 있다 → 일반 스트레이트는 SWORD 5 로.
        var mixable = hand(n(Suit.JADE, 3), n(Suit.JADE, 4), n(Suit.JADE, 5), n(Suit.SWORD, 5),
                n(Suit.JADE, 6), n(Suit.JADE, 7));

        var combos = ComboFinder.lead(mixable);

        assertThat(has(combos, HandType.STRAIGHT, 7, 5)).isTrue();
        assertThat(has(combos, HandType.STRAIGHT_FLUSH_BOMB, 7, 5)).isTrue();
        assertThat(combos).anySatisfy(c -> assertThat(c).contains(n(Suit.SWORD, 5)).hasSize(5));

        // 무늬를 바꿀 카드가 없으면 같은 5장은 폭탄으로만 나온다.
        var flushOnly = hand(n(Suit.JADE, 3), n(Suit.JADE, 4), n(Suit.JADE, 5),
                n(Suit.JADE, 6), n(Suit.JADE, 7));
        var onlyBomb = detectAll(ComboFinder.lead(flushOnly)).stream()
                .filter(h -> h.length() == 5)
                .toList();
        assertThat(onlyBomb).singleElement()
                .extracting(Hand::type).isEqualTo(HandType.STRAIGHT_FLUSH_BOMB);
    }

    @Test
    void every_combo_is_detected_as_a_hand() {
        var cards = hand(Card.mahjong(), Card.dog(), Card.phoenix(), Card.dragon(),
                n(Suit.JADE, 2), n(Suit.SWORD, 2), n(Suit.JADE, 3), n(Suit.STAR, 4),
                n(Suit.STAR, 5), n(Suit.PAGODA, 5), n(Suit.JADE, 6), n(Suit.SWORD, 10),
                n(Suit.STAR, 10), n(Suit.JADE, 14));

        var combos = ComboFinder.lead(cards);

        assertThat(combos).isNotEmpty();
        combos.forEach(ComboFinderTest::detect);
        // 개·용은 단독으로만.
        assertThat(combos).filteredOn(c -> c.stream().anyMatch(x -> x.is(Special.DOG)))
                .allSatisfy(c -> assertThat(c).hasSize(1));
        assertThat(combos).filteredOn(c -> c.stream().anyMatch(x -> x.is(Special.DRAGON)))
                .allSatisfy(c -> assertThat(c).hasSize(1));
    }

    @Test
    void follow_mode_offers_only_same_shape_plus_bombs() {
        var cards = hand(n(Suit.JADE, 4), n(Suit.SWORD, 4), n(Suit.JADE, 9), n(Suit.SWORD, 9),
                n(Suit.STAR, 11), Card.phoenix(),
                n(Suit.JADE, 12), n(Suit.SWORD, 12), n(Suit.STAR, 12), n(Suit.PAGODA, 12));
        Hand pairTop = detect(List.of(n(Suit.PAGODA, 7), n(Suit.STAR, 7)));

        var combos = ComboFinder.follow(cards, pairTop);

        assertThat(detectAll(combos)).allSatisfy(h -> assertThat(
                (h.type() == HandType.PAIR && h.length() == 2) || h.isBomb()).isTrue());
        assertThat(has(combos, HandType.PAIR, 9, 2)).isTrue();
        assertThat(has(combos, HandType.PAIR, 11, 2)).isTrue();   // J + 봉황
        assertThat(has(combos, HandType.BOMB, 12, 4)).isTrue();

        Hand singleTop = detect(List.of(n(Suit.PAGODA, 10)));
        var singles = ComboFinder.follow(cards, singleTop);
        assertThat(detectAll(singles)).allSatisfy(h -> assertThat(
                h.type() == HandType.SINGLE || h.isBomb()).isTrue());
        assertThat(singles).contains(List.of(Card.phoenix()));
    }

    @Test
    void output_does_not_depend_on_hand_list_order() {
        List<Card> cards = new ArrayList<>(List.of(Card.mahjong(), Card.phoenix(), Card.dragon(),
                n(Suit.JADE, 2), n(Suit.SWORD, 2), n(Suit.JADE, 3), n(Suit.STAR, 4),
                n(Suit.STAR, 5), n(Suit.PAGODA, 5), n(Suit.JADE, 6), n(Suit.SWORD, 6),
                n(Suit.STAR, 7), n(Suit.STAR, 8), n(Suit.JADE, 14)));
        var expected = ComboFinder.lead(cards);
        Hand top = detect(List.of(n(Suit.PAGODA, 3), n(Suit.PAGODA, 4), n(Suit.JADE, 5),
                n(Suit.PAGODA, 6), n(Suit.PAGODA, 7)));
        var expectedFollow = ComboFinder.follow(cards, top);

        Random rng = new Random(7);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(cards, rng);
            assertThat(ComboFinder.lead(cards)).isEqualTo(expected);
            assertThat(ComboFinder.follow(cards, top)).isEqualTo(expectedFollow);
        }
    }
}
