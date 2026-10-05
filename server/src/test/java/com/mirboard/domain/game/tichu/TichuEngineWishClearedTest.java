package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.card.Wish;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-126 — 소원이 사라질 때 공개 {@code WISH_CLEARED} 를 낸다. 예전에는 이벤트가 없어 클라 화면의
 * 소원 표시가 매 플레이 resync 에 기대 지워졌다(그 resync 는 순번 구멍 때문에 우연히 일어났다).
 * 소원이 사라지는 경로는 둘이다 — 소원 숫자를 낸 플레이, 용 양도(`rules-tichu.md` §9 (b)).
 */
class TichuEngineWishClearedTest {

    private static final GameContext CTX = new GameContext("test-room", List.of(1L, 2L, 3L, 4L));
    private final TichuEngine engine = new TichuEngine(CTX);

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static TichuState playingWithWish(List<PlayerState> players, int wishRank) {
        return new TichuState.Playing(players, TrickState.lead(0, Wish.active(wishRank)), -1);
    }

    @Test
    void playing_the_wished_rank_emits_public_wish_cleared() {
        TichuState state = playingWithWish(List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 7), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 10))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 13)))), 7);

        var result = engine.apply(state, 0, new TichuAction.PlayCard(List.of(n(Suit.JADE, 7))));

        assertThat(result.events())
                .filteredOn(TichuEvent.WishCleared.class::isInstance)
                .singleElement()
                .satisfies(e -> {
                    assertThat(((TichuEvent.WishCleared) e).rank()).isEqualTo(7);
                    assertThat(e.envelopeType()).isEqualTo("WISH_CLEARED");
                    assertThat(e.isPrivate()).isFalse();
                });
    }

    @Test
    void playing_another_rank_keeps_the_wish_without_event() {
        TichuState state = playingWithWish(List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 5), n(Suit.JADE, 9))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 10))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 13)))), 7);

        var result = engine.apply(state, 0, new TichuAction.PlayCard(List.of(n(Suit.JADE, 5))));

        assertThat(result.events()).noneMatch(TichuEvent.WishCleared.class::isInstance);
    }

    @Test
    void an_already_fulfilled_wish_is_not_cleared_twice() {
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 7), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7), n(Suit.SWORD, 2))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 13))));
        TichuState state = new TichuState.Playing(
                players, TrickState.lead(0, Wish.active(7).fulfill()), -1);

        var result = engine.apply(state, 0, new TichuAction.PlayCard(List.of(n(Suit.JADE, 7))));

        assertThat(result.events()).noneMatch(TichuEvent.WishCleared.class::isInstance);
    }

    @Test
    void giving_the_dragon_trick_away_clears_an_active_wish() {
        // 아무도 7 을 쥐지 않아 소원이 걸린 채로 용 트릭이 끝난다 — 양도하면 소원이 사라진다(§9 (b)).
        TichuState state = playingWithWish(List.of(
                PlayerState.initial(0, List.of(Card.dragon(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 9))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 10))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11)))), 7);
        state = engine.apply(state, 0, new TichuAction.PlayCard(List.of(Card.dragon()))).newState();
        state = engine.apply(state, 1, new TichuAction.PassTrick()).newState();
        state = engine.apply(state, 2, new TichuAction.PassTrick()).newState();
        state = engine.apply(state, 3, new TichuAction.PassTrick()).newState();

        var result = engine.apply(state, 0, new TichuAction.GiveDragonTrick(1));

        assertThat(((TichuState.Playing) result.newState()).trick().hasActiveWish()).isFalse();
        assertThat(result.events())
                .filteredOn(TichuEvent.WishCleared.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((TichuEvent.WishCleared) e).rank()).isEqualTo(7));
    }

    @Test
    void giving_the_dragon_trick_without_a_wish_emits_nothing_extra() {
        TichuState state = new TichuState.Playing(List.of(
                PlayerState.initial(0, List.of(Card.dragon(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 9))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 10))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11)))),
                TrickState.lead(0, null), -1);
        state = engine.apply(state, 0, new TichuAction.PlayCard(List.of(Card.dragon()))).newState();
        state = engine.apply(state, 1, new TichuAction.PassTrick()).newState();
        state = engine.apply(state, 2, new TichuAction.PassTrick()).newState();
        state = engine.apply(state, 3, new TichuAction.PassTrick()).newState();

        var result = engine.apply(state, 0, new TichuAction.GiveDragonTrick(1));

        assertThat(result.events()).noneMatch(TichuEvent.WishCleared.class::isInstance);
    }
}
