package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
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
import com.mirboard.testsupport.LogCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

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

    /**
     * D-131 — 다음 턴 데드라인은 방송 뒤, <b>락을 풀기 전에</b> 건다. resync 는 같은 락 안에서 상태와 남은 턴 시간을 함께
     * 읽으므로, 락을 푼 뒤에 걸면 그 틈(락을 기다리던 resync 가 곧바로 들어오는 자리)에서 새 상태 + 이전 턴의 남은 시간이
     * 나갔다. 봇 루프는 락을 푼 뒤에 건다 — 쥔 채 걸면 루프가 이 락과 부딪쳐 재시도한다.
     */
    @Test
    void the_next_turn_deadline_is_armed_before_the_lock_is_released() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
        engineAcceptsAnything();

        controller.onAction("r1", Map.of("n", 1), ME);

        InOrder order = inOrder(broadcaster, turnTimeout, lock, botScheduler);
        order.verify(broadcaster).broadcast(eq("r1"), any(), any());
        order.verify(turnTimeout).onTurnAdvanced("r1");
        order.verify(lock).release("r1");
        order.verify(botScheduler).scheduleBots("r1");
    }

    /**
     * D-131 — 락 안으로 옮긴 재무장이 던져도(Redis 순간 장애) 락은 풀리고 봇 루프는 걸린다. 예전 순서(봇 → 재무장)에서는
     * 재무장이 실패해도 봇이 걸렸다 — 그대로 두면 사람이 낸 직후 봇 차례로 넘어간 판이, 화면만 보고 기다리는 동안 아무것도
     * 진행 킥(resync·구독)을 부르지 않아 멈췄다. 탈주 계속 경로({@code DesertionService})와 같이 잡아 ERROR(스택 포함)로 남긴다.
     */
    @Test
    void a_failed_turn_rearm_still_releases_the_lock_and_schedules_bots() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
        engineAcceptsAnything();
        doThrow(new QueryTimeoutException("redis blip")).when(turnTimeout).onTurnAdvanced("r1");

        try (LogCapture logs = LogCapture.of(GameStompController.class)) {
            assertThatCode(() -> controller.onAction("r1", Map.of("n", 1), ME)).doesNotThrowAnyException();

            verify(lock).release("r1");
            verify(botScheduler).scheduleBots("r1");
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("Turn rearm after action failed").contains("roomId=r1");
                assertThat(event.getThrowableProxy()).isNotNull();
            });
        }
    }
}
