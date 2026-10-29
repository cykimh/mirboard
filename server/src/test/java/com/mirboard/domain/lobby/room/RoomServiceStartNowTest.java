package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-136·D-143 — 친구와 하기 대기실의 방장 시작(준비·정원 미만 조건 없음)(저장소는 모의 — Docker 불필요). 정원을 현재 인원으로 줄이고 시작하는 것은
 * {@code room_start_now.lua} 한 번이고(통합 테스트가 본다), 서비스는 방장 확인·게임 최소 인원 전달·결과 코드 해석·기존 시작 절차
 * ({@code onGameStart} — 좌석 섞기·{@link GameStartingEvent})를 맡는다.
 */
class RoomServiceStartNowTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;

    private final RoomRepository repository = mock(RoomRepository.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RoomService service = new RoomService(repository, games, Clock.systemUTC(), events,
            mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    private static Room room(RoomStatus status, int capacity, Long... players) {
        List<Long> ids = List.of(players);
        return new Room(ROOM_ID, "방", "ONE_CARD", HOST, status, capacity, ids.size(), ids, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    @BeforeEach
    void oneCardNeedsTwo() {
        GameDefinition def = mock(GameDefinition.class);
        when(def.minPlayers()).thenReturn(2);
        when(games.require("ONE_CARD")).thenReturn(def);
    }

    @Test
    @DisplayName("성공하면 줄어든 정원의 방으로 기존 시작 절차를 밟는다 — 메타 갱신과 GameStartingEvent(지금 인원)")
    void starts_with_current_players() {
        when(repository.findById(ROOM_ID)).thenReturn(
                Optional.of(room(RoomStatus.WAITING, 4, HOST, 10L, 11L)),
                Optional.of(room(RoomStatus.IN_GAME, 3, HOST, 10L, 11L)));
        when(repository.startNow(ROOM_ID, HOST, 2)).thenReturn(1);

        Room started = service.startNow(ROOM_ID, HOST);

        assertThat(started.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(started.capacity()).isEqualTo(3);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeast(2)).publishEvent(published.capture());
        assertThat(published.getAllValues()).anySatisfy(e -> assertThat(e).isInstanceOf(RoomChangedEvent.class));
        assertThat(published.getAllValues())
                .filteredOn(GameStartingEvent.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((GameStartingEvent) e).playerIds()).containsExactly(HOST, 10L, 11L));
    }

    @Test
    @DisplayName("방장이 아니면 NOT_HOST — 저장소를 건드리지 않는다")
    void non_host_is_refused() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, 4, HOST, 10L)));

        assertThatThrownBy(() -> service.startNow(ROOM_ID, 10L)).isInstanceOf(NotHostException.class);
        verify(repository, never()).startNow(anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("최소 인원 미만(-4)이면 NOT_ENOUGH_PLAYERS — 최소 인원을 싣는다")
    void below_minimum() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, 4, HOST)));
        when(repository.startNow(ROOM_ID, HOST, 2)).thenReturn(-4);

        assertThatThrownBy(() -> service.startNow(ROOM_ID, HOST))
                .isInstanceOfSatisfying(NotEnoughPlayersException.class, e -> assertThat(e.minPlayers()).isEqualTo(2));
        verify(events, never()).publishEvent(any(GameStartingEvent.class));
    }

    @Test
    @DisplayName("이미 시작했으면(-2) GAME_ALREADY_STARTED")
    void already_started() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room(RoomStatus.WAITING, 4, HOST, 10L)));
        when(repository.startNow(ROOM_ID, HOST, 2)).thenReturn(-2);

        assertThatThrownBy(() -> service.startNow(ROOM_ID, HOST)).isInstanceOf(GameAlreadyStartedException.class);
    }
}
