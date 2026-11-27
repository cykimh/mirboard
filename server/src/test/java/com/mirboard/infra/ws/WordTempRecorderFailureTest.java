package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.wordtemp.WordTempFixtures;
import com.mirboard.domain.game.wordtemp.WordTempFixtures.Game;
import com.mirboard.domain.game.wordtemp.WordTempGameEngine;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.scoring.LiveHintScorer;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 매치 기록기 계약 3 (D-130·D-131) — 판을 끝내는 마지막 전이(마지막 출제 차례의 "차례 끝" 마감)는 기록기(DB)가 실패해도 모두에게 나가고
 * 방이 대기실로 돌아간다. 실제 컨트롤러·진행 서비스·낱말 온도 어댑터를 묶고 저장소·브로커만 모의로 둔다. 마지막 전이는 서버 타임아웃이
 * 쓰는 멱등 {@code TICK} 으로 일으킨다(엔진 타이머와 같은 전이 — 룰 §9).
 */
class WordTempRecorderFailureTest {

    private static final String ROOM = "r1";

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final WordTempStateStore store = mock(WordTempStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<WordTempState> stored = new AtomicReference<>();

    @Test
    void the_last_transition_reaches_everyone_and_the_room_goes_back_to_waiting_even_if_recording_fails() {
        // 사람 셋·1라운드 — 출제자마다 힌트 0개로 차례가 끝나 마지막(세 번째) 차례의 "차례 끝"에 와 있다
        Game g = WordTempFixtures.game(3, Set.of(), 1).selectAll(0);
        while (!(g.state.phase() == Phase.TURN_END && g.state.history().size() == 3)) {
            g.timer();
        }
        WordTempState lastTurnEnd = g.state;
        long afterDeadline = g.now + 6_000;

        List<Long> players = List.of(10L, 30L, 20L);
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "WORD_TEMP", 10L, RoomStatus.IN_GAME, 3, 3,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 90, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new WordTempGameEngine(new GameContext(ROOM, players),
                g.engine.config(), WordTempFixtures.table(), "test", LiveHintScorer.OFF,
                new com.mirboard.domain.game.wordtemp.FakeTempFeedbackStore(), store,
                Clock.fixed(Instant.ofEpochMilli(afterDeadline), ZoneOffset.UTC),
                event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in WordTempMatchRecorder");
                }));
        stored.set(lastTurnEnd);
        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());
        when(lock.tryAcquire(ROOM)).thenReturn(true);
        when(games.require(anyString())).thenReturn(mock(GameDefinition.class));
        MirboardMetrics metrics = mock(MirboardMetrics.class);
        GameStompController controller = new GameStompController(roomService, engines, new ObjectMapper(),
                broadcaster, lock, new MatchProgressService(roomService, metrics, mock(RoomPresence.class),
                mock(WaitingLeaveGraceScheduler.class)),
                mock(BotScheduler.class), mock(TurnTimeoutScheduler.class), metrics);

        controller.onAction(ROOM, Map.of("@action", "TICK", "turnNo", lastTurnEnd.turnNo(), "attempt",
                lastTurnEnd.attempt(), "phase", "TURN_END"), new AuthPrincipal(10L, "u10"));

        assertThat(stored.get().ended()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.getLast() instanceof WordTempEvent.MatchEnded), eq(players));
        // D-153 — 사람끼리 방은 대기실로 돌아간다.
        verify(roomService).returnToWaiting(ROOM);
    }
}
