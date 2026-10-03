package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.room.NotHostException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * D-122 — 호스트·어드민 강제 종료는 <b>방 액션 락 안에서</b> FINISHED 전이와 턴 데드라인 취소를
 * 한다. 액션·봇·타임아웃은 락 안에서 방이 IN_GAME 인지 재확인한 직후 적용하므로, abort 가 락
 * 밖에서 전이하면 그 사이에 끼어 FINISHED 뒤에 액션 1건(스컬킹이면 라운드 정산·다음 라운드
 * 시작까지)이 적용됐다. 락은 탈주 처리와 같은 재시도 획득을 쓴다.
 */
class GameAbortServiceTest {

    private final RoomService rooms = mock(RoomService.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);

    private final GameAbortService service = new GameAbortService(rooms, lock, turnTimeout);

    @Test
    void host_abort_finishes_and_cancels_the_deadline_inside_the_room_lock() {
        when(lock.acquireWaiting("r1")).thenReturn(true);

        service.abortByHost("r1", 7L);

        InOrder order = inOrder(lock, rooms, turnTimeout);
        order.verify(lock).acquireWaiting("r1");
        order.verify(rooms).abortGame("r1", 7L);
        order.verify(turnTimeout).cancel("r1");
        order.verify(lock).release("r1");
    }

    @Test
    void admin_abort_finishes_and_cancels_the_deadline_inside_the_room_lock() {
        when(lock.acquireWaiting("r1")).thenReturn(true);

        service.abortByAdmin("r1");

        InOrder order = inOrder(lock, rooms, turnTimeout);
        order.verify(lock).acquireWaiting("r1");
        order.verify(rooms).adminAbortGame("r1");
        order.verify(turnTimeout).cancel("r1");
        order.verify(lock).release("r1");
    }

    /**
     * 호스트가 아닌 요청은 방 액션 락을 잡기 **전에** 거절한다. 락부터 잡으면 방 밖의 인증
     * 사용자도 POST /abort 를 반복해 라이브 방의 액션을 BUSY 로 막을 수 있다.
     */
    @Test
    void a_non_host_abort_is_rejected_before_taking_the_room_lock() {
        doThrow(new NotHostException("r1")).when(rooms).checkHostAbort("r1", 99L);

        assertThatThrownBy(() -> service.abortByHost("r1", 99L))
                .isInstanceOf(NotHostException.class);

        verify(lock, never()).acquireWaiting(anyString());
        verify(rooms, never()).abortGame(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(turnTimeout, never()).cancel(anyString());
    }

    /** 검증 실패(호스트 아님·진행 중 아님)는 그대로 던지되 락은 놓고, 데드라인은 건드리지 않는다. */
    @Test
    void a_rejected_abort_releases_the_lock_and_keeps_the_deadline() {
        when(lock.acquireWaiting("r1")).thenReturn(true);
        doThrow(new NotHostException("r1")).when(rooms).abortGame("r1", 7L);

        assertThatThrownBy(() -> service.abortByHost("r1", 7L))
                .isInstanceOf(NotHostException.class);

        verify(lock).release("r1");
        verify(turnTimeout, never()).cancel(anyString());
    }

    /**
     * 강제 종료는 탈출구다(끊긴 사람이 안 돌아올 때) — 락을 끝내 못 잡아도 거절하지 않고 락 없이
     * 끝낸다. 남의 락은 놓지 않는다. 이 비정상 경합에서만 액션 1건이 FINISHED 뒤에 적용될 수 있다.
     */
    @Test
    void an_abort_that_cannot_take_the_lock_still_ends_the_game_without_releasing_it() {
        when(lock.acquireWaiting("r1")).thenReturn(false);

        service.abortByAdmin("r1");

        verify(rooms).adminAbortGame("r1");
        verify(turnTimeout).cancel("r1");
        verify(lock, never()).release(anyString());
    }
}
