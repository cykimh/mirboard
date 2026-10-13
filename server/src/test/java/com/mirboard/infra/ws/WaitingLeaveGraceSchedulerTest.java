package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.scheduling.DeadlineQueue;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D-134 — 대기실(WAITING) 끊김 유예. 휴대폰 방장이 초대 링크를 카톡으로 보내고 기다리는 동안 소켓이 죽어도(D-79) 방과 자리가 남게,
 * 끊김 처리가 준비를 푼 뒤 이 유예를 건다. 만료는 Redis 데드라인 큐(D-96)라 어느 인스턴스든 처리하므로, 만료 시점에 방을 다시 읽어
 * <b>아직 WAITING 이고 그 사람이 플레이어이며 라이브 세션이 없을 때만</b> 내보낸다(마지막이면 방 소멸 — 기존 leave 그대로).
 */
class WaitingLeaveGraceSchedulerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final RoomPresence presence = mock(RoomPresence.class);
    private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    private final WaitingLeaveGraceScheduler scheduler =
            new WaitingLeaveGraceScheduler(roomService, presence, deadlines, 120);

    private static Room room(RoomStatus status, List<Long> players, Set<Long> spectators) {
        return new Room("r1", "방", "ONE_CARD", players.isEmpty() ? 0L : players.get(0),
                status, 4, players.size(), players, spectators, TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    @Test
    @DisplayName("자기 큐 이름은 waiting-leave — 탈주 유예(desertion)와 섞이지 않는다")
    void kind_is_waiting_leave() {
        assertThat(scheduler.kind()).isEqualTo("waiting-leave").isNotEqualTo(DesertionGraceScheduler.KIND);
    }

    @Test
    @DisplayName("유예를 걸면 {방}:{사람} 을 설정한 초(기본 120초) 뒤로 데드라인 큐에 넣는다")
    void schedule_puts_member_after_grace() {
        scheduler.scheduleGrace("r1", 7L);

        verify(deadlines).schedule("waiting-leave", "r1:7", Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("취소는 대기 중이었는지를 돌려준다")
    void cancel_reports_whether_pending() {
        when(deadlines.cancelExisting("waiting-leave", "r1:7")).thenReturn(true);

        assertThat(scheduler.cancelIfPending("r1", 7L)).isTrue();
        assertThat(scheduler.cancelIfPending("r1", 8L)).isFalse();
    }

    @Test
    @DisplayName("만료: 아직 대기실이고 플레이어이며 라이브 세션이 없으면 내보낸다")
    void expiry_leaves_when_still_waiting_player_without_session() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.WAITING, List.of(7L, 8L), Set.of()));

        scheduler.handle("r1:7");

        verify(roomService).leaveRoom("r1", 7L);
    }

    @Test
    @DisplayName("만료: 혼자 남은 방장이어도 내보낸다 — 마지막이면 방 소멸은 기존 leave 가 한다")
    void expiry_leaves_last_player_too() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.WAITING, List.of(7L), Set.of()));

        scheduler.handle("r1:7");

        verify(roomService).leaveRoom("r1", 7L);
    }

    @Test
    @DisplayName("만료: 그사이 돌아왔으면(라이브 세션 — 다른 인스턴스로 붙었어도) 그대로 둔다")
    void expiry_keeps_seat_when_back() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.WAITING, List.of(7L, 8L), Set.of()));
        when(presence.hasLiveSession(7L, "r1")).thenReturn(true);

        scheduler.handle("r1:7");

        verify(roomService, never()).leaveRoom(anyString(), anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = RoomStatus.class, names = {"IN_GAME", "FINISHED"})
    @DisplayName("만료: 방 상태가 바뀌었으면(시작·끝) 아무것도 안 한다 — 시작된 게임의 좌석은 불변(D-122)")
    void expiry_ignores_room_no_longer_waiting(RoomStatus status) {
        when(roomService.getRoom("r1")).thenReturn(room(status, List.of(7L, 8L), Set.of()));

        scheduler.handle("r1:7");

        verify(roomService, never()).leaveRoom(anyString(), anyLong());
    }

    @Test
    @DisplayName("만료: 방이 없어졌으면 아무것도 안 한다")
    void expiry_ignores_missing_room() {
        when(roomService.getRoom("r1")).thenThrow(new RoomNotFoundException("r1"));

        assertThatCode(() -> scheduler.handle("r1:7")).doesNotThrowAnyException();

        verify(roomService, never()).leaveRoom(anyString(), anyLong());
    }

    @Test
    @DisplayName("만료: 이미 나갔으면(직접 '나가기'·관전으로 바뀜) 아무것도 안 한다")
    void expiry_ignores_user_no_longer_player() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.WAITING, List.of(8L), Set.of(7L)));

        scheduler.handle("r1:7");

        verify(roomService, never()).leaveRoom(anyString(), anyLong());
    }

    @Test
    @DisplayName("만료: 다시 읽은 뒤 내보내기 사이에 나갔거나 방이 사라져도 예외를 밖으로 내지 않는다")
    void expiry_swallows_leave_race() {
        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.WAITING, List.of(7L, 8L), Set.of()));
        doThrow(new NotInRoomException("r1")).when(roomService).leaveRoom("r1", 7L);

        assertThatCode(() -> scheduler.handle("r1:7")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-separator", "r1:not-a-number", "r1:"})
    @DisplayName("member 형식이 틀리면 방을 보지 않고 버린다")
    void malformed_member_is_dropped(String member) {
        assertThatCode(() -> scheduler.handle(member)).doesNotThrowAnyException();

        verifyNoInteractions(roomService, presence);
    }
}
