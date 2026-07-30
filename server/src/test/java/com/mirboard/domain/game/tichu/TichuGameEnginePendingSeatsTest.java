package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.PassCardsSelection;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import com.mirboard.infra.messaging.DomainEventBus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * D-98 — {@code pendingSeats} 는 BotScheduler 의 {@code hasPendingAction} 과
 * TurnTimeoutScheduler 의 {@code pendingSeat} 로 <b>중복</b>돼 있던 두 switch 를 하나로
 * 합친 결과다. 두 호출자의 요구가 다르므로(봇=동시 대기 좌석 전부 / 타임아웃=첫 좌석)
 * 여기서 양쪽 계약을 함께 고정한다.
 *
 * <p>특히 Dealing/Passing 이 <b>복수</b>여야 한다 — 단수로 두면 좌석 3 의 봇이 좌석 1
 * 사람의 선언을 기다리며 영원히 멈춘다.
 */
class TichuGameEnginePendingSeatsTest {

    private final TichuGameEngine engine = new TichuGameEngine(
            new GameContext("r1", List.of(1L, 2L, 3L, 4L)),
            mock(TichuGameStateStore.class),
            mock(TichuMatchStateStore.class),
            mock(TichuRoundStarter.class),
            mock(DomainEventBus.class));

    private static final Card A_CARD = Card.normal(Suit.JADE, 5);

    private static List<PlayerState> fourPlayers() {
        return IntStream.range(0, 4)
                .mapToObj(seat -> PlayerState.initial(seat, List.of(A_CARD)))
                .toList();
    }

    @Test
    void dealing_pends_every_seat_that_has_not_readied() {
        var state = new TichuState.Dealing(fourPlayers(), 8, Set.of(1), Map.of());

        assertThat(engine.pendingSeats(state)).containsExactly(0, 2, 3);
        assertThat(engine.pendingSeat(state)).isEqualTo(0);   // 타임아웃은 첫 좌석만.
    }

    @Test
    void dealing_pends_nobody_when_all_readied() {
        var state = new TichuState.Dealing(fourPlayers(), 14, Set.of(0, 1, 2, 3), Map.of());

        assertThat(engine.pendingSeats(state)).isEmpty();
        assertThat(engine.pendingSeat(state)).isEqualTo(-1);
    }

    @Test
    void passing_pends_every_seat_that_has_not_submitted() {
        var selection = new PassCardsSelection(A_CARD, A_CARD, A_CARD);
        var state = new TichuState.Passing(fourPlayers(), Map.of(0, selection, 2, selection));

        assertThat(engine.pendingSeats(state)).containsExactly(1, 3);
    }

    @Test
    void playing_pends_only_the_seat_on_turn() {
        var state = new TichuState.Playing(fourPlayers(), TrickState.lead(2, null), -1);

        assertThat(engine.pendingSeats(state)).containsExactly(2);
    }

    @Test
    void playing_pends_nobody_when_the_seat_on_turn_has_finished() {
        List<PlayerState> players = new java.util.ArrayList<>(fourPlayers());
        players.set(2, players.get(2).withFinishedOrder(1));
        var state = new TichuState.Playing(players, TrickState.lead(2, null), 2);

        assertThat(engine.pendingSeats(state)).isEmpty();
        assertThat(engine.pendingSeat(state)).isEqualTo(-1);
    }

    /** 용으로 트릭을 가져간 좌석은 양도를 마칠 때까지 차례를 붙잡는다 — 다른 좌석은 대기 아님. */
    @Test
    void dragon_give_pending_holds_the_taker_seat() {
        Hand dragon = HandDetector.detect(List.of(Card.dragon())).orElseThrow();
        var trick = new TrickState(0, 3, dragon, 3, Set.of(), List.of(dragon),
                List.of(Card.dragon()), null);
        var state = new TichuState.Playing(fourPlayers(), trick, -1);

        assertThat(engine.pendingSeats(state)).containsExactly(3);
    }

    @Test
    void round_end_pends_nobody() {
        var state = new TichuState.RoundEnd(fourPlayers(), 50, 50);

        assertThat(engine.pendingSeats(state)).isEmpty();
        assertThat(engine.isRoundOver(state)).isTrue();
    }
}
