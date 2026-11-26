package com.mirboard.domain.game.wordtemp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.wordtemp.WordTempGameDefinition;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.words.WordTempWords;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.ws.GameEventBroadcaster;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 판 시작(D-319) — 상태를 <b>저장한 뒤에</b> 공개 {@code WORD_SELECT_STARTED} 하나를 방송한다. 방이 IN_GAME 이 됐다는 소식이 먼저 나가서 첫
 * resync 가 저장보다 빠를 수 있는데, 이 이벤트를 받은 클라가 다시 resync 하면 상태가 반드시 있다(순서가 뒤집히면 그 resync 도 빈손이다).
 */
class WordTempRoundStarterTest {

    private static final WordTempWords WORDS = new WordTempWords();
    private static final String ROOM = "room-1";
    private static final List<Long> PLAYERS = List.of(11L, 12L, 13L, 14L);

    private final WordTempStateStore store = mock(WordTempStateStore.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final TurnTimeoutScheduler timeouts = mock(TurnTimeoutScheduler.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final WordTempRoundStarter starter = new WordTempRoundStarter(store, WORDS,
            Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC), bots, timeouts, broadcaster);

    private static GameStartingEvent starting(String gameType) {
        return new GameStartingEvent(ROOM, gameType, PLAYERS, 0);
    }

    @Test
    void saves_the_state_before_announcing_word_select_then_arms_the_timers() {
        starter.onGameStarting(starting(WordTempGameDefinition.ID));

        ArgumentCaptor<WordTempState> saved = ArgumentCaptor.forClass(WordTempState.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<? extends GameEvent>> sent = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(store, broadcaster, bots, timeouts);
        order.verify(store).save(eq(ROOM), saved.capture());
        order.verify(broadcaster).broadcast(eq(ROOM), sent.capture(), eq(PLAYERS));
        order.verify(bots).scheduleBots(ROOM);
        order.verify(timeouts).onTurnAdvanced(ROOM);

        assertThat(saved.getValue().phase()).isEqualTo(Phase.WORD_SELECT);
        assertThat(saved.getValue().round()).isEqualTo(1);
        // 공개 이벤트 하나뿐 — 후보(비공개)는 방송하지 않는다. 내 후보는 resync 의 비공개 뷰로 간다.
        assertThat(sent.getValue()).hasSize(1);
        GameEvent event = sent.getValue().get(0);
        assertThat(event).isInstanceOf(WordTempEvent.WordSelectStarted.class);
        assertThat(event.isPrivate()).isFalse();
        assertThat(event.sequenced()).isTrue();
        assertThat(((WordTempEvent.WordSelectStarted) event).round()).isEqualTo(1);
        assertThat(((WordTempEvent.WordSelectStarted) event).remainingMs()).isPositive();
    }

    @Test
    void a_failed_announce_does_not_stop_the_match_from_starting() {
        doThrow(new IllegalStateException("broker down")).when(broadcaster).broadcast(any(), any(), any());

        starter.onGameStarting(starting(WordTempGameDefinition.ID));

        verify(store).save(eq(ROOM), any());
        verify(bots).scheduleBots(ROOM);
        verify(timeouts).onTurnAdvanced(ROOM);
    }

    @Test
    void ignores_other_games() {
        starter.onGameStarting(starting("TICHU"));

        verifyNoInteractions(store, broadcaster, bots, timeouts);
    }
}
