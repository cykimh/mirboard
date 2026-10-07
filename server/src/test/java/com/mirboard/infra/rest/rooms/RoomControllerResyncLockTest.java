package com.mirboard.infra.rest.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.ResyncNotAvailableException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.GameProgressKick;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameAbortService;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.infra.ws.RoomPresence;
import com.mirboard.infra.ws.RoomSeq;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * D-126 — resync 는 방 액션 락 안에서 상태·순번·뷰를 읽는다. 액션은 같은 락 안에서 저장 →
 * 브로드캐스트(순번 발급)를 끝내므로, 락 밖에서 따로 읽으면 그 사이 액션이 끼어 스냅샷과
 * {@code eventSeq} 가 어긋난다 — 클라가 이벤트를 두 번 적용하거나 하나를 놓친다. 예전에는 매
 * 플레이 resync 가 다음 플레이에서 그걸 지웠지만, 매 플레이 resync 가 사라지면 라운드 내내 남는다.
 */
class RoomControllerResyncLockTest {

    private static final long ME = 10L;
    private static final String ROOM = "r1";

    private final RoomService rooms = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final RoomSeq seqs = mock(RoomSeq.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);
    private final GameProgressKick kick = mock(GameProgressKick.class);
    private final TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);

    private final RoomController controller = new RoomController(
            rooms, engines, seqs, mock(DesertionService.class), mock(RoomPresence.class),
            mock(RoomChipStore.class), mock(GameAbortService.class), lock, kick, turnTimeout);

    private final AuthPrincipal me = new AuthPrincipal(ME, "me");

    private void givenGameInProgress() {
        List<Long> players = List.of(ME, 20L, 30L, 40L);
        Room room = new Room(ROOM, "방", "TICHU", ME, RoomStatus.IN_GAME, 4, players.size(),
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0,
                Set.of());
        when(rooms.getRoom(ROOM)).thenReturn(room);
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.phaseName(state)).thenReturn("PLAYING");
        when(engine.publicView(state)).thenReturn("table");
        when(engine.privateView(any(), anyInt())).thenReturn(Optional.of("hand"));
        when(seqs.current(ROOM)).thenReturn(42L);
    }

    @Test
    void snapshot_and_seq_are_read_while_holding_the_room_lock() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(true);

        var res = controller.resync(ROOM, me);

        assertThat(res.eventSeq()).isEqualTo(42L);
        assertThat(res.tableView()).isEqualTo("table");
        assertThat(res.privateHand()).isEqualTo("hand");
        InOrder order = inOrder(lock, engine, seqs);
        order.verify(lock).acquireWaiting(ROOM);
        order.verify(engine).loadState();
        order.verify(seqs).current(ROOM);
        order.verify(engine).publicView(state);
        order.verify(lock).release(ROOM);
    }

    /** 락을 못 잡으면(약 3초) 예전처럼 잠금 없이 읽는다 — 남의 락을 지우면 안 된다. */
    @Test
    void falls_back_to_an_unlocked_read_without_releasing_a_lock_it_does_not_hold() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(false);

        var res = controller.resync(ROOM, me);

        assertThat(res.eventSeq()).isEqualTo(42L);
        verify(lock, never()).release(anyString());
    }

    @Test
    void releases_the_lock_when_there_is_no_state_yet() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(true);
        when(engine.loadState()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.resync(ROOM, me))
                .isInstanceOf(ResyncNotAvailableException.class);
        verify(lock).release(ROOM);
        verify(kick, never()).kick(anyString(), anyLong());
    }

    /**
     * D-130 — resync 는 클라가 방을 다시 보는 순간이라, 멈춘 진행(재기동 뒤 봇 차례·사라진 엔진 타이머)을 다시 거는 자리다.
     * 락을 푼 <b>뒤</b>에 건다 — 킥은 비동기라 응답을 늦추지 않고, 락 안에서 걸면 킥이 건 봇 루프가 이 락과 부딪쳐
     * 쓸데없이 재시도한다(킥 자신은 락을 잡지 않는다).
     */
    @Test
    void the_progress_kick_goes_out_after_the_lock_is_released() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(true);

        controller.resync(ROOM, me);

        InOrder order = inOrder(lock, kick);
        order.verify(lock).release(ROOM);
        order.verify(kick).kick(ROOM, ME);
    }

    @Test
    void an_unlocked_fallback_read_still_kicks() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(false);

        controller.resync(ROOM, me);

        verify(kick).kick(ROOM, ME);
    }

    /**
     * D-131 — 남은 턴 시간도 스냅샷과 같은 락 안에서 읽는다. 진행 경로는 저장·방송·다음 턴 데드라인 재무장을 모두 이 락 안에서
     * 끝내므로, 락 안에서 읽은 값은 함께 읽은 상태의 턴 것이다(락 밖에서 읽으면 새 상태 + 이전 턴의 남은 시간이 나올 수 있다).
     */
    @Test
    void the_turn_remaining_time_is_read_inside_the_lock_with_the_snapshot() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(true);
        when(engine.pendingSeats(state)).thenReturn(List.of(0));
        when(turnTimeout.turnRemaining(any())).thenReturn(Optional.of(Duration.ofMillis(12_345)));

        var res = controller.resync(ROOM, me);

        assertThat(res.turnRemainingMs()).isEqualTo(12_345L);
        InOrder order = inOrder(lock, engine, turnTimeout);
        order.verify(lock).acquireWaiting(ROOM);
        order.verify(engine).loadState();
        order.verify(turnTimeout).turnRemaining(any());
        order.verify(lock).release(ROOM);
    }

    /** 기다리는 좌석이 없으면(원카드 경쟁 창·끝난 매치) 걸린 데드라인이 있어도 발화해 봐야 아무 일이 없다 — 남은 시간이 아니다. */
    @Test
    void nobody_on_the_clock_means_no_remaining_time() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(true);
        when(engine.pendingSeats(state)).thenReturn(List.of());

        var res = controller.resync(ROOM, me);

        assertThat(res.turnRemainingMs()).isNull();
        verify(turnTimeout, never()).turnRemaining(any());
    }

    /** 턴 제한이 꺼졌거나 지금 세대의 데드라인이 없으면 null — 클라는 세지 않는다. */
    @Test
    void no_armed_deadline_means_no_remaining_time() {
        givenGameInProgress();
        when(lock.acquireWaiting(ROOM)).thenReturn(true);
        when(engine.pendingSeats(state)).thenReturn(List.of(0));
        when(turnTimeout.turnRemaining(any())).thenReturn(Optional.empty());

        assertThat(controller.resync(ROOM, me).turnRemainingMs()).isNull();
    }
}
