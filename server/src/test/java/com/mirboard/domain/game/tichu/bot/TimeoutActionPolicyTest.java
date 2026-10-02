package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-109 — 소원이 PlayCard 에 동봉되면서 봇 후보에 랭크 변형이 생겼다. 타임아웃
 * 자동 플레이는 **결정적**이어야 하므로 소원을 걸지 않는 쪽으로 고정한다.
 */
class TimeoutActionPolicyTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static PlayerState p(int seat, Card... cards) {
        return PlayerState.initial(seat, List.of(cards));
    }

    @Test
    void timeout_on_mahjong_lead_plays_mahjong_without_wish() {
        var players = List.of(
                p(0, Card.mahjong(), n(Suit.SWORD, 7)),
                p(1, n(Suit.JADE, 10)),
                p(2, n(Suit.STAR, 9)),
                p(3, n(Suit.PAGODA, 4)));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        TichuAction chosen = TimeoutActionPolicy.choose(state, 0);

        assertThat(chosen).isInstanceOf(TichuAction.PlayCard.class);
        var play = (TichuAction.PlayCard) chosen;
        // 가장 약한 단일 = 마작(rank 1), 그리고 소원은 걸지 않는다.
        assertThat(play.cards()).containsExactly(Card.mahjong());
        assertThat(play.wishRank()).isNull();
    }
}
