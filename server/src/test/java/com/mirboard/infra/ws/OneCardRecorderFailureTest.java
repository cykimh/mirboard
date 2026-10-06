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
import com.mirboard.domain.game.onecard.OneCardGameEngine;
import com.mirboard.domain.game.onecard.RaceSettings;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
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
 * S5 — 매치를 끝내는 마지막 카드는 기록기(DB)가 실패해도 모두에게 나가고 방이 끝난다. 실제 컨트롤러·진행 서비스·원카드
 * 어댑터를 묶고 저장소·브로커만 모의로 둔다. 예전에는 동기 기록기의 예외가 컨트롤러까지 올라와 저장 뒤의 방송과 FINISHED
 * 전이를 건너뛰었다 — 상태는 끝났는데 클라는 직전 화면에 멈추고 방은 IN_GAME 에 남았다(C-M2·P-F7).
 */
class OneCardRecorderFailureTest {

    private static final String ROOM = "r1";
    private static final PlayingCard HEART_9 = PlayingCard.of(Suit.HEART, 9);

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final OneCardStateStore store = mock(OneCardStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<OneCardState> stored = new AtomicReference<>();

    /** 좌석 0 이 마지막 한 장(♥9)을 들고 차례. */
    private static OneCardState lastCardForSeatZero() {
        List<PlayingCard> second = List.of(PlayingCard.of(Suit.SPADE, 4), PlayingCard.of(Suit.SPADE, 6));
        PlayingCard top = PlayingCard.of(Suit.HEART, 5);
        List<PlayingCard> used = new ArrayList<>(second);
        used.add(HEART_9);
        used.add(top);
        List<PlayingCard> drawPile = Deck.all().stream().filter(card -> !used.contains(card)).toList();
        return new OneCardState(List.of(List.of(HEART_9), second), drawPile, List.of(top), 0, 1, null, 0, null,
                List.of(), 0, 0, 1, null);
    }

    @Test
    void the_last_card_reaches_everyone_and_the_room_finishes_even_if_recording_fails() {
        List<Long> players = List.of(10L, 30L);
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "ONE_CARD", 10L, RoomStatus.IN_GAME, 2, 2,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new OneCardGameEngine(new GameContext(ROOM, players), store,
                Clock.systemUTC(), new Random(7), RaceSettings.DEFAULT, event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in OneCardMatchRecorder");
                }));
        stored.set(lastCardForSeatZero());
        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());
        when(lock.tryAcquire(ROOM)).thenReturn(true);
        when(games.require(anyString())).thenReturn(mock(GameDefinition.class));
        MirboardMetrics metrics = mock(MirboardMetrics.class);
        GameStompController controller = new GameStompController(roomService, engines, new ObjectMapper(),
                broadcaster, lock, new MatchProgressService(roomService, metrics, games),
                mock(BotScheduler.class), mock(TurnTimeoutScheduler.class), metrics);

        controller.onAction(ROOM, Map.of("@action", "PLAY_CARD", "card", Map.of("suit", "HEART", "rank", 9)),
                new AuthPrincipal(10L, "u10"));

        assertThat(stored.get().ended()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.stream().anyMatch(OneCardEvent.CardPlayed.class::isInstance)
                        && events.getLast() instanceof OneCardEvent.MatchEnded), eq(players));
        verify(roomService).markFinished(ROOM);
    }
}
