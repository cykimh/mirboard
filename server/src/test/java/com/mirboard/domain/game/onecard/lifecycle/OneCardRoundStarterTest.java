package com.mirboard.domain.game.onecard.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.onecard.Dealer;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** D-128 — 방이 게임을 시작하면 원카드 판을 나눠 저장하고 봇 루프·타이머를 건다. */
class OneCardRoundStarterTest {

    private final OneCardStateStore store = mock(OneCardStateStore.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final TurnTimeoutScheduler timers = mock(TurnTimeoutScheduler.class);

    private OneCardRoundStarter starter(long seed) {
        return new OneCardRoundStarter(store, new Random(seed), bots, timers);
    }

    private static GameStartingEvent event(String gameType, int players) {
        List<Long> ids = LongStream.range(0, players).map(i -> 100 + i).boxed().toList();
        return new GameStartingEvent("room-1", gameType, ids, 0);
    }

    @Test
    void a_one_card_game_is_dealt_saved_and_handed_to_the_bots_and_timers() {
        starter(1).onGameStarting(event("ONE_CARD", 4));

        ArgumentCaptor<OneCardState> saved = ArgumentCaptor.forClass(OneCardState.class);
        InOrder order = inOrder(store, bots, timers);
        order.verify(store).save(eq("room-1"), saved.capture());
        order.verify(bots).scheduleBots("room-1");
        order.verify(timers).onTurnAdvanced("room-1");
        OneCardState state = saved.getValue();
        assertThat(state.hands()).hasSize(4).allSatisfy(h -> assertThat(h).hasSize(Dealer.HAND_SIZE));
        assertThat(state.topCard().isNormal()).isTrue();
        assertThat(state.turnSeat()).isBetween(0, 3);
        OneCardInvariantChecker.check(state);
    }

    @Test
    void the_same_seed_deals_the_same_table() {
        ArgumentCaptor<OneCardState> first = ArgumentCaptor.forClass(OneCardState.class);
        ArgumentCaptor<OneCardState> second = ArgumentCaptor.forClass(OneCardState.class);

        starter(9).onGameStarting(event("ONE_CARD", 3));
        verify(store).save(anyString(), first.capture());
        OneCardStateStore other = mock(OneCardStateStore.class);
        new OneCardRoundStarter(other, new Random(9), bots, timers).onGameStarting(event("ONE_CARD", 3));
        verify(other).save(anyString(), second.capture());

        assertThat(second.getValue()).isEqualTo(first.getValue());
    }

    @Test
    void other_games_are_ignored() {
        starter(1).onGameStarting(event("TICHU", 4));

        verifyNoInteractions(store, bots, timers);
    }

    @Test
    void seat_counts_outside_two_to_six_are_skipped() {
        starter(1).onGameStarting(event("ONE_CARD", 7));

        verify(store, never()).save(anyString(), any());
        verifyNoInteractions(bots, timers);
    }
}
