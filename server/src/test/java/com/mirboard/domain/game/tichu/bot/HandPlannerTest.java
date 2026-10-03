package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.hand.HandType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-118 — {@link HandPlanner} 는 모든 결정 지점이 같이 쓰는 평가 함수다. 대표 손패에서
 * 묶음 수·컨트롤·루저가 기대대로 나오는지, 봉황·폭탄·개의 취급이 설계와 같은지 고정한다.
 */
class HandPlannerTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static HandPlanner.Plan plan(Card... cards) {
        return HandPlanner.plan(HandPlanner.mask(List.of(cards)));
    }

    private static List<HandType> types(HandPlanner.Plan plan) {
        return plan.groups().stream().map(HandPlanner.Group::type).toList();
    }

    @Test
    void typical_hand_counts_groups_controls_and_losers() {
        var plan = plan(n(Suit.JADE, 2), n(Suit.SWORD, 3), n(Suit.STAR, 4), n(Suit.PAGODA, 5),
                n(Suit.JADE, 6), n(Suit.JADE, 9), n(Suit.SWORD, 9), n(Suit.JADE, 13),
                n(Suit.JADE, 14), Card.dragon());

        // 2-6 스트레이트 · 9 페어 · K · A · 용.
        assertThat(plan.groups()).hasSize(5);
        assertThat(types(plan)).containsExactlyInAnyOrder(HandType.STRAIGHT, HandType.PAIR,
                HandType.SINGLE, HandType.SINGLE, HandType.SINGLE);
        assertThat(plan.controls()).isEqualTo(2);   // A 단독, 용
        assertThat(plan.losers()).isEqualTo(1);     // 9 페어
        assertThat(plan.cost()).isEqualTo(6);
        assertThat(HandPlanner.cost(HandPlanner.mask(plan.groups().stream()
                .flatMap(g -> HandPlanner.cards(g.mask()).stream()).toList())))
                .isEqualTo(plan.cost());
    }

    @Test
    void phoenix_joins_a_combo_only_when_it_merges_groups() {
        // 3·4·_·6·7 + 봉황 → 스트레이트 한 묶음.
        var merged = plan(n(Suit.JADE, 3), n(Suit.SWORD, 4), n(Suit.STAR, 6), n(Suit.PAGODA, 7),
                Card.phoenix(), n(Suit.JADE, 11));
        assertThat(merged.groups()).hasSize(2);
        assertThat(merged.groups()).anySatisfy(g -> {
            assertThat(g.type()).isEqualTo(HandType.STRAIGHT);
            assertThat(g.mask() & HandPlanner.PHOENIX).isNotZero();
        });

        // 짝을 맞출 단일만 있으면 봉황은 단독 컨트롤로 남는다.
        var alone = plan(n(Suit.JADE, 3), n(Suit.SWORD, 9), n(Suit.STAR, 13), Card.phoenix());
        assertThat(alone.groups()).hasSize(4);
        assertThat(alone.groups()).anySatisfy(g ->
                assertThat(g.mask()).isEqualTo(HandPlanner.PHOENIX));
        assertThat(alone.controls()).isEqualTo(1);
        assertThat(alone.losers()).isEqualTo(2);
    }

    @Test
    void bombs_are_reserved_before_other_groups() {
        var plan = plan(n(Suit.JADE, 7), n(Suit.SWORD, 7), n(Suit.STAR, 7), n(Suit.PAGODA, 7),
                n(Suit.JADE, 5), n(Suit.SWORD, 6), n(Suit.STAR, 8), n(Suit.PAGODA, 9));

        long sevens = HandPlanner.rankBits(7);
        assertThat(plan.groups()).anySatisfy(g -> {
            assertThat(g.type()).isEqualTo(HandType.BOMB);
            assertThat(g.mask()).isEqualTo(sevens);
        });
        // 7 이 폭탄에 묶여 5-9 스트레이트는 만들어지지 않는다.
        assertThat(plan.groups()).filteredOn(g -> g.type() != HandType.BOMB)
                .allSatisfy(g -> assertThat(g.mask() & sevens).isZero());
        assertThat(plan.groups()).hasSize(5);
        assertThat(plan.controls()).isEqualTo(1);
    }

    @Test
    void straight_flush_is_reserved_as_a_bomb() {
        var plan = plan(n(Suit.STAR, 3), n(Suit.STAR, 4), n(Suit.STAR, 5), n(Suit.STAR, 6),
                n(Suit.STAR, 7), n(Suit.JADE, 7), n(Suit.JADE, 12));

        assertThat(types(plan)).containsExactlyInAnyOrder(
                HandType.STRAIGHT_FLUSH_BOMB, HandType.SINGLE, HandType.SINGLE);
    }

    @Test
    void dog_is_a_group_but_never_a_loser() {
        var plan = plan(Card.dog(), n(Suit.JADE, 3));

        assertThat(plan.groups()).hasSize(2);
        assertThat(plan.losers()).isEqualTo(1);   // 3 만
        assertThat(plan.controls()).isZero();
    }

    @Test
    void pairs_combine_into_consecutive_pairs_and_full_house() {
        var plan = plan(n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.JADE, 4), n(Suit.SWORD, 4),
                n(Suit.JADE, 9), n(Suit.SWORD, 9), n(Suit.STAR, 9),
                n(Suit.JADE, 12), n(Suit.SWORD, 12));

        assertThat(types(plan)).containsExactlyInAnyOrder(
                HandType.CONSECUTIVE_PAIRS, HandType.FULL_HOUSE);
        assertThat(plan.losers()).isZero();
        assertThat(plan.cost()).isEqualTo(2);
    }

    @Test
    void picks_the_variant_with_fewer_groups() {
        // 3-4-5-5-6-6-7: 스트레이트 먼저면 3-7 + 5 + 6 (3묶음),
        // 세트 먼저면 5566 연속페어 + 3·4·7 (4묶음) → 스트레이트 먼저가 이긴다.
        var plan = plan(n(Suit.JADE, 3), n(Suit.JADE, 4), n(Suit.JADE, 5), n(Suit.SWORD, 5),
                n(Suit.SWORD, 6), n(Suit.STAR, 6), n(Suit.PAGODA, 7));

        assertThat(plan.groups()).hasSize(3);
        assertThat(types(plan)).contains(HandType.STRAIGHT);
    }

    @Test
    void groups_partition_the_hand_and_ignore_list_order() {
        List<Card> cards = new ArrayList<>(List.of(Card.mahjong(), Card.dog(), Card.phoenix(),
                Card.dragon(), n(Suit.JADE, 2), n(Suit.SWORD, 2), n(Suit.JADE, 3),
                n(Suit.STAR, 4), n(Suit.STAR, 5), n(Suit.PAGODA, 5), n(Suit.JADE, 10),
                n(Suit.SWORD, 10), n(Suit.STAR, 10), n(Suit.JADE, 14)));
        long hand = HandPlanner.mask(cards);
        var expected = HandPlanner.plan(hand);

        long union = 0;
        for (var g : expected.groups()) {
            assertThat(union & g.mask()).isZero();
            union |= g.mask();
        }
        assertThat(union).isEqualTo(hand);

        Random rng = new Random(3);
        for (int i = 0; i < 10; i++) {
            Collections.shuffle(cards, rng);
            assertThat(HandPlanner.plan(HandPlanner.mask(cards))).isEqualTo(expected);
        }
    }
}
