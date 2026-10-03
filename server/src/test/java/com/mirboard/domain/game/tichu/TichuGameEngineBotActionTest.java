package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.infra.messaging.DomainEventBus;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-118 — 포트 {@code botAction} 이 휴리스틱 정책에 위임하는지. 이전 랜덤 봇은 선언을 하지
 * 않았고(Dealing 후보가 Ready 하나뿐) 시드에 따라 수가 달랐다.
 */
class TichuGameEngineBotActionTest {

    private final TichuGameEngine engine = new TichuGameEngine(
            new GameContext("r1", List.of(1L, 2L, 3L, 4L)),
            mock(TichuGameStateStore.class),
            mock(TichuMatchStateStore.class),
            mock(TichuRoundStarter.class),
            mock(DomainEventBus.class));

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static TichuState.Dealing strongEight() {
        var mine = List.of(Card.dragon(), Card.phoenix(), n(Suit.JADE, 14), n(Suit.SWORD, 14),
                n(Suit.STAR, 9), n(Suit.PAGODA, 7), n(Suit.JADE, 5), n(Suit.SWORD, 3));
        var other = List.of(n(Suit.STAR, 2));
        return new TichuState.Dealing(List.of(PlayerState.initial(0, mine),
                PlayerState.initial(1, other), PlayerState.initial(2, other),
                PlayerState.initial(3, other)), 8, Set.of(), Map.of());
    }

    @Test
    void bot_action_uses_the_heuristic_and_declares_grand_tichu() {
        assertThat(engine.botAction(strongEight(), 0, new Random(1)))
                .isInstanceOf(TichuAction.DeclareGrandTichu.class);
    }

    @Test
    void bot_action_ignores_the_random_argument() {
        var a = engine.botAction(strongEight(), 0, new Random(1));
        var b = engine.botAction(strongEight(), 0, new Random(999));
        assertThat(a).isEqualTo(b);
    }
}
