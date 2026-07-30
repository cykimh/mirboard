package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-98 이후 본 서비스는 게임을 모른다 — 여기서 검증하는 것은 <b>인프라 절차</b>다:
 * 봇/비참가자/비-IN_GAME 가드, 락 해제, 엔진이 탈주를 인정했을 때의 브로드캐스트 +
 * 방 FINISHED 마킹.
 *
 * <p>"상대팀이 승리한다" 같은 <b>게임 규칙</b>은
 * {@code com.mirboard.domain.game.tichu.TichuGameEngineDesertionTest} 가 검증한다 —
 * 그쪽이 이제 그 판단이 사는 곳이다.
 */
class DesertionServiceTest {

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final BotUserRegistry bots = mock(BotUserRegistry.class);
    private final GameEngine engine = mock(GameEngine.class);

    private final DesertionService service = new DesertionService(
            roomService, engines, broadcaster, lock, bots);

    private static Room inGame(List<Long> players) {
        return new Room("r1", "방", "TICHU", players.get(0), RoomStatus.IN_GAME,
                4, players.size(), players, Set.of(), TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    @Test
    void accepted_desertion_broadcasts_and_finishes_room() {
        List<Long> players = List.of(10L, 20L, 30L, 40L);
        when(bots.isBot(10L)).thenReturn(false);
        when(lock.tryAcquire("r1")).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(inGame(players));
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.desert(eq(0), eq(10L), any())).thenReturn(true);

        boolean processed = service.processDesertion("r1", 10L);

        assertThat(processed).isTrue();
        verify(broadcaster).broadcast(eq("r1"), any(), eq(players));
        verify(roomService).markFinished("r1");
        verify(lock).release("r1");
    }

    /** 엔진이 탈주로 보지 않으면(티츄: 리매치 대기 방, D-82) 매치를 건드리지 않는다. */
    @Test
    void desertion_declined_by_game_is_noop() {
        when(bots.isBot(10L)).thenReturn(false);
        when(lock.tryAcquire("r1")).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(inGame(List.of(10L, 20L, 30L, 40L)));
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.desert(anyInt(), anyLong(), any())).thenReturn(false);

        boolean processed = service.processDesertion("r1", 10L);

        assertThat(processed).isFalse();
        verify(broadcaster, never()).broadcast(any(), any(), any());
        verify(roomService, never()).markFinished(any());
        verify(lock).release("r1"); // 락은 반드시 해제.
    }

    @Test
    void bot_deserter_is_guarded_no_lock_no_engine() {
        when(bots.isBot(99L)).thenReturn(true);

        boolean processed = service.processDesertion("r1", 99L);

        assertThat(processed).isFalse();
        verify(lock, never()).tryAcquire(any());
        verify(engines, never()).forRoom(any());
    }

    @Test
    void already_finished_room_is_idempotent_noop() {
        when(bots.isBot(10L)).thenReturn(false);
        when(lock.tryAcquire("r1")).thenReturn(true);
        Room finished = new Room("r1", "방", "TICHU", 10L, RoomStatus.FINISHED,
                4, 4, List.of(10L, 20L, 30L, 40L), Set.of(), TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, 0, Set.of());
        when(roomService.getRoom("r1")).thenReturn(finished);

        boolean processed = service.processDesertion("r1", 10L);

        assertThat(processed).isFalse();
        verify(engines, never()).forRoom(any());
        verify(roomService, never()).markFinished(any());
        verify(lock).release("r1"); // 락은 반드시 해제.
    }

    @Test
    void non_participant_is_noop() {
        when(bots.isBot(77L)).thenReturn(false);
        when(lock.tryAcquire("r1")).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(inGame(List.of(10L, 20L, 30L, 40L)));

        boolean processed = service.processDesertion("r1", 77L);

        assertThat(processed).isFalse();
        verify(engines, never()).forRoom(any());
        verify(lock).release("r1");
    }
}
