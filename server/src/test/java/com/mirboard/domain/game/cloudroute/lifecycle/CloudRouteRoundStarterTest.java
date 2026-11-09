package com.mirboard.domain.game.cloudroute.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mirboard.domain.game.cloudroute.invariant.CloudRouteInvariantChecker;
import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** D-212 — 방이 게임을 시작하면 구름항로 판을 만들어 저장하고 봇 루프·턴 타이머를 건다. */
class CloudRouteRoundStarterTest {

    private final CloudRouteStateStore store = mock(CloudRouteStateStore.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final TurnTimeoutScheduler timers = mock(TurnTimeoutScheduler.class);

    private CloudRouteRoundStarter starter(CloudRouteStateStore s, long seed) {
        return new CloudRouteRoundStarter(s, new Random(seed), bots, timers);
    }

    private static GameStartingEvent event(String gameType, int players) {
        List<Long> ids = LongStream.range(0, players).map(i -> 100 + i).boxed().toList();
        return new GameStartingEvent("room-1", gameType, ids, 0);
    }

    @Test
    void a_cloud_route_game_is_set_up_saved_and_handed_to_the_bots_and_timers() {
        starter(store, 1).onGameStarting(event("CLOUD_ROUTE", 4));

        ArgumentCaptor<CloudRouteState> saved = ArgumentCaptor.forClass(CloudRouteState.class);
        InOrder order = inOrder(store, bots, timers);
        order.verify(store).save(eq("room-1"), saved.capture());
        order.verify(bots).scheduleBots("room-1");
        order.verify(timers).onTurnAdvanced("room-1");
        CloudRouteState state = saved.getValue();
        assertThat(state.seatCount()).isEqualTo(4);
        assertThat(state.roundLimit()).isEqualTo(20);
        CloudRouteInvariantChecker.check(state);
    }

    @Test
    void the_room_match_length_reaches_the_round_limit() {
        List<Long> ids = LongStream.range(0, 3).map(i -> 100 + i).boxed().toList();
        starter(store, 1).onGameStarting(new GameStartingEvent("room-1", "CLOUD_ROUTE", ids, 0,
                java.util.Map.of(com.mirboard.domain.game.core.RoomOption.MATCH_LENGTH, "LONG")));
        ArgumentCaptor<CloudRouteState> saved = ArgumentCaptor.forClass(CloudRouteState.class);
        verify(store).save(eq("room-1"), saved.capture());
        assertThat(saved.getValue().roundLimit()).isEqualTo(32);
    }

    @Test
    void the_same_seed_sets_up_the_same_table() {
        ArgumentCaptor<CloudRouteState> first = ArgumentCaptor.forClass(CloudRouteState.class);
        ArgumentCaptor<CloudRouteState> second = ArgumentCaptor.forClass(CloudRouteState.class);
        starter(store, 9).onGameStarting(event("CLOUD_ROUTE", 3));
        verify(store).save(anyString(), first.capture());
        CloudRouteStateStore other = mock(CloudRouteStateStore.class);
        starter(other, 9).onGameStarting(event("CLOUD_ROUTE", 3));
        verify(other).save(anyString(), second.capture());
        assertThat(second.getValue()).isEqualTo(first.getValue());
    }

    @Test
    void other_games_and_seat_counts_outside_two_to_four_are_skipped() {
        starter(store, 1).onGameStarting(event("ONE_CARD", 4));
        starter(store, 1).onGameStarting(event("CLOUD_ROUTE", 5));
        verify(store, never()).save(anyString(), any());
        verifyNoInteractions(bots, timers);
    }
}
