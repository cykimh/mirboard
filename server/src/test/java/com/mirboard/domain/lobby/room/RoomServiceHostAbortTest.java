package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-135 — 방장 강제 종료는 방장 말고 모두 봇인 판에서만(저장소는 모의 — Docker 불필요). 강제 종료는 매치 기록·레이팅을 남기지
 * 않으므로 사람끼리 하는 판에서 허용하면 지고 있는 방장이 판을 깰 수 있다. 사람 판의 끊김은 탈주 유예가, 멈춘 판은 운영자 강제
 * 종료(D-86 — 이 검사를 거치지 않는다)가 맡는다.
 */
class RoomServiceHostAbortTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;
    private static final long FRIEND = 10L;
    private static final List<Long> BOTS = List.of(900L, 901L, 902L);

    private final RoomRepository repository = mock(RoomRepository.class);
    private final RoomService service = new RoomService(repository, mock(GameRegistry.class), Clock.systemUTC(),
            mock(ApplicationEventPublisher.class), mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    private static Room room(RoomStatus status, Long... players) {
        List<Long> ids = List.of(players);
        List<Integer> botSeats = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (BOTS.contains(ids.get(i))) {
                botSeats.add(i);
            }
        }
        return new Room(ROOM_ID, "방", "ONE_CARD", HOST, status, ids.size(), ids.size(), ids, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, !botSeats.isEmpty(), List.copyOf(botSeats), 1000, 0, 0, Set.of());
    }

    private void stored(Room state) {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(state));
    }

    @Test
    @DisplayName("방장 말고 모두 봇인 진행 중 판은 강제 종료한다")
    void host_aborts_a_game_against_bots() {
        stored(room(RoomStatus.IN_GAME, HOST, 900L, 901L, 902L));

        service.abortGame(ROOM_ID, HOST);

        verify(repository).markFinished(eq(ROOM_ID), anyLong());
    }

    @Test
    @DisplayName("방장 자리가 0번이 아니어도(좌석 섞기) 나머지가 모두 봇이면 허용한다")
    void host_seat_can_be_anywhere() {
        stored(room(RoomStatus.IN_GAME, 900L, HOST, 901L));

        assertThatCode(() -> service.checkHostAbort(ROOM_ID, HOST)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사람이 한 명이라도 더 있으면 거절한다 — HUMAN_PLAYERS_PRESENT, 방은 그대로")
    void rejects_when_another_human_plays() {
        stored(room(RoomStatus.IN_GAME, HOST, FRIEND, 900L, 901L));

        assertThatThrownBy(() -> service.abortGame(ROOM_ID, HOST))
                .isInstanceOf(HumanPlayersPresentException.class);
        verify(repository, never()).markFinished(anyString(), anyLong());
    }

    @Test
    @DisplayName("사람끼리만 하는 판도 거절한다")
    void rejects_an_all_human_game() {
        stored(room(RoomStatus.IN_GAME, HOST, FRIEND, 11L, 12L));

        assertThatThrownBy(() -> service.checkHostAbort(ROOM_ID, HOST))
                .isInstanceOf(HumanPlayersPresentException.class);
    }

    @Test
    @DisplayName("방장이 아니면 지금처럼 NOT_HOST 가 먼저다")
    void non_host_is_still_not_host() {
        stored(room(RoomStatus.IN_GAME, HOST, 900L, 901L, 902L));

        assertThatThrownBy(() -> service.checkHostAbort(ROOM_ID, 900L)).isInstanceOf(NotHostException.class);
    }

    @Test
    @DisplayName("진행 중이 아니면 지금처럼 GAME_NOT_IN_PROGRESS 다")
    void waiting_room_is_still_not_in_progress() {
        stored(room(RoomStatus.WAITING, HOST, FRIEND));

        assertThatThrownBy(() -> service.checkHostAbort(ROOM_ID, HOST))
                .isInstanceOf(GameNotInProgressException.class);
    }
}
