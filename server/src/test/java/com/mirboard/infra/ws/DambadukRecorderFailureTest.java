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
import com.mirboard.domain.game.dambaduk.DambadukBoards;
import com.mirboard.domain.game.dambaduk.DambadukGameEngine;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 매치 기록기 계약 3 (D-130·D-131) — 판을 끝내는 마지막 수(담으로 모두 갈라짐)는 기록기(DB)가 실패해도 모두에게 나가고 방이 대기실로
 * 돌아간다. 실제 컨트롤러·진행 서비스·담바둑 어댑터를 묶고 저장소·브로커만 모의로 둔다.
 */
class DambadukRecorderFailureTest {

    private static final String ROOM = "r1";

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final DambadukStateStore store = mock(DambadukStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<DambadukState> stored = new AtomicReference<>();

    /** 룰 §12.3 그림에서 C4|D4 만 열린 판, 흑(좌석 0) 차례 — C5(돌 6) → C4 로 막으면 끝. */
    private static DambadukState oneWallFromTheEnd() {
        return DambadukBoards.playing(2, 0,
                "+---+---+---+---+---+---+---+",
                "| .   a   . | .   .   b   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | B   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| A   .   . | .   .   .   B |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   b   .   . |",
                "+   +   +   +---+---+---+---+",
                "| .   .   A | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   a   . | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | .   .   .   . |",
                "+---+---+---+---+---+---+---+");
    }

    @Test
    void the_separating_move_reaches_everyone_and_the_room_goes_back_to_waiting_even_if_recording_fails() {
        List<Long> players = List.of(10L, 30L);
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "DAMBADUK", 10L, RoomStatus.IN_GAME, 2, 2,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new DambadukGameEngine(new GameContext(ROOM, players), store,
                event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in DambadukMatchRecorder");
                }));
        stored.set(oneWallFromTheEnd());
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

        controller.onAction(ROOM, Map.of("@action", "MOVE_STONE", "stoneId", 6, "path", List.of("UP"), "wall", "RIGHT"),
                new AuthPrincipal(10L, "u10"));

        assertThat(stored.get().isOver()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.stream().anyMatch(DambadukEvent.StoneMoved.class::isInstance)
                        && events.getLast() instanceof DambadukEvent.MatchEnded), eq(players));
        // D-153 — 사람끼리 방은 대기실로 돌아간다.
        verify(roomService).returnToWaiting(ROOM);
    }
}
