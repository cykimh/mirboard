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
import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.ArtilleryFixtures;
import com.mirboard.domain.game.artillery.ArtilleryGameEngine;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * D-130·D-131 패턴(다른 게임과 같은 고정) — 씨앗포 매치를 끝내는 마지막 발사는 기록기(DB)가 실패해도 모두에게 나가고 방이 끝난다.
 * 실제 컨트롤러·진행 서비스·씨앗포 어댑터를 묶고 저장소·브로커만 모의로 둔다.
 */
class ArtilleryRecorderFailureTest {

    private static final String ROOM = "r1";

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final ArtilleryStateStore store = mock(ArtilleryStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<ArtilleryState> stored = new AtomicReference<>();

    @Test
    void the_last_shot_reaches_everyone_and_the_room_goes_back_to_waiting_even_if_recording_fails() {
        List<Long> players = List.of(10L, 30L);
        ArtilleryState s = ArtilleryFixtures.start(21L, 2);
        int me = s.turnSeat();
        s = ArtilleryFixtures.withUnit(s, s.unit(1 - me).withHp(1));
        ArtilleryAction.Fire hit = ArtilleryFixtures.directHit(s, 1 - me, Shell.PEA);
        assertThat(hit).isNotNull();

        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "ARTILLERY", 10L, RoomStatus.IN_GAME, 2, 2,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 30, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new ArtilleryGameEngine(new GameContext(ROOM, players),
                new ArtilleryEngine(), store, Clock.systemUTC(), event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in ArtilleryMatchRecorder");
                }));
        stored.set(s);
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

        long userId = players.get(me);
        controller.onAction(ROOM, Map.of("@action", "FIRE", "moveDx", 0, "angle", hit.angle(), "power", hit.power(),
                "shell", "PEA"), new AuthPrincipal(userId, "u" + userId));

        assertThat(stored.get().ended()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.stream().anyMatch(ArtilleryEvent.ShotResolved.class::isInstance)
                        && events.getLast() instanceof ArtilleryEvent.MatchEnded), eq(players));
        verify(roomService).returnToWaiting(ROOM);
    }
}
