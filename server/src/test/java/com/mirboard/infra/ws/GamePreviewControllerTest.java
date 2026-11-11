package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.testsupport.LogCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-229 — 게임 중립 미리보기 컨트롤러. 받아들일 수 없는 프레임은 조용히 버리고(방송 0·ERROR 0), 게임이 걸러 준 공개·순번 없는 이벤트만 방
 * 토픽으로 낸다. 락·저장·턴 시계와는 무관하다(이 컨트롤러는 그 의존성을 아예 갖지 않는다 — 생성자 인자 넷).
 */
class GamePreviewControllerTest {

    record Aim(int angle) implements GameAction {
    }

    record Shown(int angle) implements GameEvent {
        @Override
        public String envelopeType() {
            return "SHOWN";
        }

        @Override
        public boolean sequenced() {
            return false;
        }
    }

    /** 계약 위반 — 순번을 쓴다. */
    record Sequenced() implements GameEvent {
        @Override
        public String envelopeType() {
            return "SEQUENCED";
        }
    }

    /** 계약 위반 — 비공개. */
    record Secret() implements GameEvent {
        @Override
        public String envelopeType() {
            return "SECRET";
        }

        @Override
        public int privateSeat() {
            return 0;
        }

        @Override
        public boolean sequenced() {
            return false;
        }
    }

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);

    private final GamePreviewController controller =
            new GamePreviewController(roomService, engines, new ObjectMapper(), broadcaster);

    private static final AuthPrincipal ME = new AuthPrincipal(10L, "me");
    private static final AuthPrincipal WATCHER = new AuthPrincipal(30L, "watcher");
    private static final List<Long> PLAYERS = List.of(20L, 10L);

    private static Room room(RoomStatus status) {
        return new Room("r1", "방", "ANY", 20L, status, 2, 2, PLAYERS, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    private void inGameWith(Optional<GameEvent> result) {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
        when(engines.forRoom(any())).thenReturn(engine);
        doReturn(Aim.class).when(engine).actionType();
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.preview(eq(state), anyInt(), any())).thenReturn(result);
    }

    @Test
    void a_filtered_event_goes_out_once_without_a_sequence_and_nothing_else_happens() {
        inGameWith(Optional.of(new Shown(45)));
        controller.onPreview("r1", Map.of("angle", 45), ME);
        verify(engine).preview(state, 1, new Aim(45));
        verify(broadcaster).broadcast("r1", List.of(new Shown(45)), PLAYERS);
        verify(broadcaster, never()).sendErrorTo(any(Long.class), anyString(), anyString(), anyString());
        verify(engine, never()).saveState(any());
        verify(engine, never()).apply(any(), anyInt(), any());
    }

    @Test
    void empty_from_the_game_broadcasts_nothing() {
        inGameWith(Optional.empty());
        controller.onPreview("r1", Map.of("angle", 45), ME);
        verify(engine).preview(state, 1, new Aim(45));
        verifyNoInteractions(broadcaster);
    }

    @Test
    void waiting_rooms_spectators_missing_rooms_and_bad_payloads_are_dropped_silently() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.WAITING));
        controller.onPreview("r1", Map.of("angle", 45), ME);
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED));
        controller.onPreview("r1", Map.of("angle", 45), ME);
        verifyNoInteractions(engines);

        inGameWith(Optional.of(new Shown(45)));
        controller.onPreview("r1", Map.of("angle", 45), WATCHER);   // 좌석 없음(관전자)
        controller.onPreview("r1", Map.of(), ME);                    // 빈 본문
        controller.onPreview("r1", Map.of("angle", "not-a-number"), ME);   // 역직렬화 실패
        when(engine.loadState()).thenReturn(Optional.empty());
        controller.onPreview("r1", Map.of("angle", 45), ME);          // 상태 없음
        when(roomService.getRoom("gone")).thenThrow(new RoomNotFoundException("gone"));
        controller.onPreview("gone", Map.of("angle", 45), ME);
        verify(engine, never()).preview(any(), anyInt(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void events_that_break_the_contract_are_dropped_with_a_warning() {
        for (GameEvent bad : List.<GameEvent>of(new Sequenced(), new Secret())) {
            inGameWith(Optional.of(bad));
            try (LogCapture logs = LogCapture.of(GamePreviewController.class)) {
                controller.onPreview("r1", Map.of("angle", 45), ME);
                assertThat(logs.events()).anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("breaks the port contract").contains(bad.envelopeType());
                });
            }
        }
        verify(broadcaster, never()).broadcast(anyString(), anyList(), anyList());
    }

    @Test
    void an_engine_failure_is_logged_and_swallowed() {
        inGameWith(Optional.empty());
        when(engine.preview(eq(state), anyInt(), any())).thenThrow(new IllegalStateException("boom"));
        try (LogCapture logs = LogCapture.of(GamePreviewController.class)) {
            controller.onPreview("r1", Map.of("angle", 45), ME);
            assertThat(logs.events()).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("Preview failed"));
        }
        verifyNoInteractions(broadcaster);
    }
}
