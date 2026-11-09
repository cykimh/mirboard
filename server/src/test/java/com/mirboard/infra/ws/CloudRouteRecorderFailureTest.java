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
import com.mirboard.domain.game.cloudroute.CloudRouteGameEngine;
import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent;
import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.cloudroute.state.SeatState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * D-212 — 구름항로도 매치를 끝내는 마지막 굴림은 기록기(DB)가 실패해도 모두에게 나가고 방이 끝난다(`docs/game-port.md` "매치 기록기
 * 계약" 3). 실제 컨트롤러·진행 서비스·구름항로 어댑터를 묶고 저장소·브로커만 모의로 둔다.
 */
class CloudRouteRecorderFailureTest {

    private static final String ROOM = "r1";
    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final CloudRouteStateStore store = mock(CloudRouteStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<CloudRouteState> stored = new AtomicReference<>();

    /**
     * 2인 마지막 라운드(30)의 마지막 좌석 1 이 현금 0·연속 더블 2 로 굴린다 — 어떤 눈이 나와도 결정 없이 끝난다(더블이면 세 번째 더블로
     * 소용돌이, 아니면 어디에 서도 살 돈·지을 땅·날 돈이 없고, 세관이면 파산). 주사위가 무작위인 어댑터로도 결정적인 장면이다.
     */
    private static CloudRouteState lastRollOfTheMatch() {
        List<Integer> none = java.util.Collections.nCopies(Board.SIZE, -1);
        List<Integer> zero = java.util.Collections.nCopies(Board.SIZE, 0);
        return new CloudRouteState(List.of(new SeatState(1_500, 0, false, 0), new SeatState(0, 0, false, 0)), none, zero,
                -1, -1, Letter.fullDeck(), List.of(), 0, 1, 30, Economy.roundLimit(2), 2, true, false, List.of(),
                List.of(), new Pending.AwaitRoll(), List.of(), 1, null);
    }

    @Test
    void the_last_roll_reaches_everyone_and_the_room_goes_back_to_waiting_even_if_recording_fails() {
        List<Long> players = List.of(10L, 30L);
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "CLOUD_ROUTE", 10L, RoomStatus.IN_GAME, 2, 2,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new CloudRouteGameEngine(new GameContext(ROOM, players), store,
                new Random(7), event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in CloudRouteMatchRecorder");
                }));
        stored.set(lastRollOfTheMatch());
        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());
        when(lock.tryAcquire(ROOM)).thenReturn(true);
        when(games.require(anyString())).thenReturn(mock(GameDefinition.class));
        MirboardMetrics metrics = mock(MirboardMetrics.class);
        GameStompController controller = new GameStompController(roomService, engines, new ObjectMapper(),
                broadcaster, lock, new MatchProgressService(roomService, metrics, mock(RoomPresence.class), mock(WaitingLeaveGraceScheduler.class)),
                mock(BotScheduler.class), mock(TurnTimeoutScheduler.class), metrics);

        controller.onAction(ROOM, Map.of("@action", "ROLL"), new AuthPrincipal(30L, "u30"));

        assertThat(stored.get().ended()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.stream().anyMatch(CloudRouteEvent.DiceRolled.class::isInstance)
                        && events.getLast() instanceof CloudRouteEvent.MatchEnded), eq(players));
        // D-153 — 사람끼리 방은 대기실로 돌아간다.
        verify(roomService).returnToWaiting(ROOM);
    }
}
