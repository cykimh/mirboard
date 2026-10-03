package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-118 — 테스트 전용 그리디 기준선의 패스가 설계대로 "실제 랭크 최저 3장"인지. 기준선이
 * 봉황·용을 넘기면 휴리스틱 고유 효과가 부풀려진다(리뷰 지적: 정규 비트 순서 앞 3장 = 개·봉황·마작).
 */
class GreedyBaselinePolicyTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static TichuState.Passing passing(List<Card> mine) {
        List<Card> filler = List.of(n(Suit.PAGODA, 14));
        return new TichuState.Passing(List.of(PlayerState.initial(0, mine),
                PlayerState.initial(1, filler), PlayerState.initial(2, filler),
                PlayerState.initial(3, filler)), Map.of());
    }

    @Test
    void passes_the_three_lowest_ranks_and_keeps_the_phoenix() {
        var mine = List.of(Card.phoenix(), Card.dragon(), n(Suit.JADE, 14), n(Suit.SWORD, 9),
                n(Suit.STAR, 4), n(Suit.JADE, 3), n(Suit.PAGODA, 2));

        var pass = (TichuAction.PassCards) GreedyBaselinePolicy.choose(passing(mine), 0);

        assertThat(List.of(pass.toLeft(), pass.toPartner(), pass.toRight()))
                .containsExactly(n(Suit.PAGODA, 2), n(Suit.JADE, 3), n(Suit.STAR, 4));
    }

    @Test
    void dog_and_mahjong_rank_below_two_while_the_phoenix_ranks_above_ace() {
        var mine = List.of(Card.phoenix(), Card.dog(), Card.mahjong(), n(Suit.JADE, 2),
                n(Suit.SWORD, 14));

        var pass = (TichuAction.PassCards) GreedyBaselinePolicy.choose(passing(mine), 0);

        assertThat(List.of(pass.toLeft(), pass.toPartner(), pass.toRight()))
                .containsExactly(Card.dog(), Card.mahjong(), n(Suit.JADE, 2));
    }

    @Test
    void ties_on_rank_break_by_canonical_card_order() {
        var mine = List.of(Card.phoenix(), n(Suit.PAGODA, 3), n(Suit.JADE, 3), n(Suit.STAR, 3),
                n(Suit.SWORD, 3));

        var pass = (TichuAction.PassCards) GreedyBaselinePolicy.choose(passing(mine), 0);

        assertThat(List.of(pass.toLeft(), pass.toPartner(), pass.toRight()))
                .doesNotContain(Card.phoenix())
                .containsExactlyElementsOf(HandPlanner.cards(HandPlanner.mask(List.of(
                        n(Suit.PAGODA, 3), n(Suit.JADE, 3), n(Suit.STAR, 3), n(Suit.SWORD, 3))))
                        .subList(0, 3));
    }
}
