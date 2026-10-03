package com.mirboard.infra.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
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
import org.junit.jupiter.api.Test;

/**
 * D-122 — 끝난 방(FINISHED)의 STOMP 액션은 적용하지 않는다. 게임 중립 가드(방 상태)라 강제
 * 종료처럼 엔진 상태로는 매치가 안 끝난 경우도 막는다. 락 전 확인에 더해 <b>락 안에서
 * 재확인</b>한다 — 탈주 MATCH_ENDED 는 이 락 안에서 방을 FINISHED 로 만든다.
 */
class GameStompControllerGuardTest {

    /** 이 테스트 전용 액션 — 역직렬화 대상이 될 뿐 내용은 관심이 아니다. */
    record Poke(int n) implements GameAction {
    }

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final MatchProgressService matchProgress = mock(MatchProgressService.class);
    private final BotScheduler botScheduler = mock(BotScheduler.class);
    private final TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);

    private final GameStompController controller = new GameStompController(
            roomService, engines, new ObjectMapper(), broadcaster, lock, matchProgress,
            botScheduler, turnTimeout, mock(MirboardMetrics.class));

    private static final AuthPrincipal ME = new AuthPrincipal(10L, "me");

    private static Room room(RoomStatus status) {
        return new Room("r1", "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    private void engineAcceptsAnything() {
        when(engines.forRoom(any())).thenReturn(engine);
        doReturn(Poke.class).when(engine).actionType();
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.apply(eq(state), anyInt(), any()))
                .thenReturn(new GameEngine.Result(state, List.of()));
        when(lock.tryAcquire("r1")).thenReturn(true);
    }

    @Test
    void an_action_in_a_finished_room_is_rejected_without_touching_the_game() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED));
        engineAcceptsAnything();

        controller.onAction("r1", Map.of("n", 1), ME);

        verify(broadcaster).sendErrorTo(eq(10L), eq("r1"), eq("GAME_NOT_IN_PROGRESS"),
                anyString());
        verify(engine, never()).apply(any(), anyInt(), any());
        verify(engine, never()).saveState(any());
        verify(broadcaster, never()).broadcast(anyString(), any(), any());
        verify(botScheduler, never()).scheduleBots(anyString());
        verify(turnTimeout, never()).onTurnAdvanced(anyString());
    }

    @Test
    void a_room_that_finished_while_the_action_waited_for_the_lock_is_rejected() {
        when(roomService.getRoom("r1"))
                .thenReturn(room(RoomStatus.IN_GAME), room(RoomStatus.FINISHED));
        engineAcceptsAnything();

        controller.onAction("r1", Map.of("n", 1), ME);

        verify(broadcaster).sendErrorTo(eq(10L), eq("r1"), eq("GAME_NOT_IN_PROGRESS"),
                anyString());
        verify(engine, never()).apply(any(), anyInt(), any());
        verify(lock).release("r1");
    }

    /** 대조군 — 진행 중인 방의 액션은 그대로 적용된다. */
    @Test
    void an_action_in_an_in_game_room_is_applied() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
        engineAcceptsAnything();

        controller.onAction("r1", Map.of("n", 1), ME);

        verify(engine).apply(eq(state), eq(0), eq(new Poke(1)));
        verify(engine).saveState(state);
    }
}
