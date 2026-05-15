package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RandomBotPolicyTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    @Test
    void choose_returns_null_when_no_legal_action() {
        var policy = new RandomBotPolicy(42L);
        var state = new TichuState.RoundEnd(
                List.of(PlayerState.initial(0, List.of()),
                        PlayerState.initial(1, List.of()),
                        PlayerState.initial(2, List.of()),
                        PlayerState.initial(3, List.of())),
                100, 50);

        assertThat(policy.choose(state, 0)).isNull();
    }

    @Test
    void choose_picks_ready_in_dealing() {
        var policy = new RandomBotPolicy(42L);
        var dealing = new TichuState.Dealing(
                List.of(
                        PlayerState.initial(0, List.of(n(Suit.JADE, 2))),
                        PlayerState.initial(1, List.of(n(Suit.SWORD, 5))),
                        PlayerState.initial(2, List.of(n(Suit.STAR, 7))),
                        PlayerState.initial(3, List.of(n(Suit.PAGODA, 9)))),
                8,
                Set.of(),
                Map.of());

        TichuAction action = policy.choose(dealing, 1);

        assertThat(action).isInstanceOf(TichuAction.Ready.class);
    }

    @Test
    void same_seed_produces_same_choice_in_playing() {
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 2), n(Suit.SWORD, 5), n(Suit.STAR, 7))),
                PlayerState.initial(1, List.of(n(Suit.JADE, 10))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 13))));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        var p1 = new RandomBotPolicy(12345L);
        var p2 = new RandomBotPolicy(12345L);

        TichuAction a1 = p1.choose(state, 0);
        TichuAction a2 = p2.choose(state, 0);

        assertThat(a1).isNotNull();
        assertThat(a1).isEqualTo(a2);
    }

    @Test
    void choose_returns_legal_action() {
        // 단순 검증: 100 회 반복해서 항상 PlayCard 또는 PassTrick 만 반환.
        // currentTurnSeat=0, currentTop 있음 → PlayCard 가 canBeat 검증을 통과해야 합법.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 14))),  // A
                PlayerState.initial(1, List.of(n(Suit.SWORD, 12))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 13))));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        var policy = new RandomBotPolicy(99L);
        for (int i = 0; i < 100; i++) {
            TichuAction action = policy.choose(state, 0);
            assertThat(action).isInstanceOf(TichuAction.PlayCard.class);
        }
    }
}
