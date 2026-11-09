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
import com.mirboard.domain.game.omok.OmokEngine;
import com.mirboard.domain.game.omok.OmokGameEngine;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import com.mirboard.domain.game.omok.state.OmokState;
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
 * 매치 기록기 계약 3 (D-130·D-131) — 5목을 만드는 마지막 수는 기록기(DB)가 실패해도 모두에게 나가고 방이 대기실로 돌아간다.
 * 실제 컨트롤러·진행 서비스·오목 어댑터를 묶고 저장소·브로커만 모의로 둔다.
 */
class OmokRecorderFailureTest {

    private static final String ROOM = "r1";

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final OmokStateStore store = mock(OmokStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<OmokState> stored = new AtomicReference<>();

    /** 흑(좌석 0)이 (3..6, 7) 넷을 둔 흑 차례. */
    private static OmokState blackFourInARow() {
        OmokEngine e = new OmokEngine();
        OmokState s = e.startMatch(OmokMode.SOLO, OmokRule.RENJU, 2, 0, 0);
        int[][] moves = {{3, 7}, {0, 0}, {4, 7}, {0, 2}, {5, 7}, {0, 4}, {6, 7}, {0, 6}};
        for (int[] m : moves) {
            s = e.apply(s, s.turnSeat(), new OmokAction.Place(m[0], m[1])).newState();
        }
        return s;
    }

    @Test
    void the_winning_stone_reaches_everyone_and_the_room_goes_back_to_waiting_even_if_recording_fails() {
        List<Long> players = List.of(10L, 30L);
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "OMOK", 10L, RoomStatus.IN_GAME, 2, 2,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new OmokGameEngine(new GameContext(ROOM, players), store,
                event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in OmokMatchRecorder");
                }));
        stored.set(blackFourInARow());
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

        controller.onAction(ROOM, Map.of("@action", "PLACE_STONE", "x", 7, "y", 7), new AuthPrincipal(10L, "u10"));

        assertThat(stored.get().isOver()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.stream().anyMatch(OmokEvent.StonePlaced.class::isInstance)
                        && events.getLast() instanceof OmokEvent.MatchEnded), eq(players));
        // D-153 — 사람끼리 방은 대기실로 돌아간다.
        verify(roomService).returnToWaiting(ROOM);
    }
}
