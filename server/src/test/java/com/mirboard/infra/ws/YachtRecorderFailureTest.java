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
import com.mirboard.domain.game.yacht.YachtGameEngine;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.state.TurnState;
import com.mirboard.domain.game.yacht.state.YachtState;
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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * D-130·D-131 패턴(원카드·스컬킹과 같은 고정) — 요트 매치를 끝내는 마지막 기록은 기록기(DB)가 실패해도 모두에게 나가고 방이 끝난다.
 * 실제 컨트롤러·진행 서비스·요트 어댑터를 묶고 저장소·브로커만 모의로 둔다.
 */
class YachtRecorderFailureTest {

    private static final String ROOM = "r1";

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final YachtStateStore store = mock(YachtStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<YachtState> stored = new AtomicReference<>();

    private static Scorecard all(Category except) {
        Scorecard card = Scorecard.EMPTY;
        for (Category c : Category.values()) {
            if (c != except) {
                card = card.with(c, 0);
            }
        }
        return card;
    }

    /** 12라운드, 좌석 1 이 요트 칸만 남기고 6 다섯 개를 굴린 상태 — 기록하면 매치가 끝난다. */
    private static YachtState lastScoreForSeatOne() {
        return new YachtState(List.of(all(null), all(Category.YACHT)), 12, 1,
                new TurnState(Dice.of(6, 6, 6, 6, 6), HoldMask.NONE, 1, 0L), List.of(), 0, null);
    }

    @Test
    void the_last_score_reaches_everyone_and_the_room_goes_back_to_waiting_even_if_recording_fails() {
        List<Long> players = List.of(10L, 30L);
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "YACHT", 10L, RoomStatus.IN_GAME, 2, 2,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new YachtGameEngine(new GameContext(ROOM, players), store,
                Clock.systemUTC(), new Random(7), event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in YachtMatchRecorder");
                }));
        stored.set(lastScoreForSeatOne());
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

        controller.onAction(ROOM, Map.of("@action", "SCORE", "category", "YACHT"), new AuthPrincipal(30L, "u30"));

        assertThat(stored.get().ended()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.stream().anyMatch(YachtEvent.Scored.class::isInstance)
                        && events.getLast() instanceof YachtEvent.MatchEnded), eq(players));
        // D-153 — 사람끼리 방은 대기실로 돌아간다.
        verify(roomService).returnToWaiting(ROOM);
    }
}
