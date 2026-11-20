package com.mirboard.domain.game.dambaduk.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.dambaduk.invariant.DambadukInvariantChecker;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** 방이 게임을 시작하면 선을 추첨해 시작 판을 저장하고 봇 루프·타이머를 건다 (룰 §2·§3). */
class DambadukRoundStarterTest {

    private final DambadukStateStore store = mock(DambadukStateStore.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final TurnTimeoutScheduler timers = mock(TurnTimeoutScheduler.class);

    private DambadukRoundStarter starter(long seed) {
        return new DambadukRoundStarter(store, new Random(seed), bots, timers);
    }

    private static GameStartingEvent event(String gameType, int players) {
        List<Long> ids = LongStream.range(0, players).map(i -> 100 + i).boxed().toList();
        return new GameStartingEvent("room-1", gameType, ids, 0);
    }

    @Test
    void a_two_player_game_is_saved_and_handed_to_the_bots_and_timers() {
        starter(1).onGameStarting(event("DAMBADUK", 2));
        ArgumentCaptor<DambadukState> saved = ArgumentCaptor.forClass(DambadukState.class);
        InOrder order = inOrder(store, bots, timers);
        order.verify(store).save(eq("room-1"), saved.capture());
        order.verify(bots).scheduleBots("room-1");
        order.verify(timers).onTurnAdvanced("room-1");
        DambadukState s = saved.getValue();
        assertThat(s.seatCount()).isEqualTo(2);
        assertThat(s.phase()).isEqualTo(Phase.PLACING);
        assertThat(s.stones()).hasSize(4);
        assertThat(s.turnSeat()).isEqualTo(s.firstSeat());
        DambadukInvariantChecker.check(s);
    }

    @Test
    void three_players_start_empty_and_every_seat_can_be_first() {
        DambadukRoundStarter one = starter(42);                   // 한 난수열로 여러 판 — 이웃 시드의 첫 값은 서로 닮는다
        Set<Integer> firsts = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            clearInvocations(store);
            one.onGameStarting(event("DAMBADUK", 3));
            ArgumentCaptor<DambadukState> saved = ArgumentCaptor.forClass(DambadukState.class);
            verify(store).save(eq("room-1"), saved.capture());
            DambadukState s = saved.getValue();
            assertThat(s.stones()).isEmpty();
            assertThat(s.jumpsLeft()).containsExactly(1, 1, 1);
            firsts.add(s.firstSeat());
            DambadukInvariantChecker.check(s);
        }
        assertThat(firsts).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void other_games_and_bad_seat_counts_are_ignored() {
        starter(1).onGameStarting(event("OMOK", 2));
        starter(1).onGameStarting(event("DAMBADUK", 4));
        starter(1).onGameStarting(event("DAMBADUK", 1));
        verify(store, never()).save(anyString(), any());
        verify(bots, never()).scheduleBots(anyString());
    }
}
