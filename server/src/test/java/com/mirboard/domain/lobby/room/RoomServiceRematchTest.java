package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.messaging.DomainEventBus;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-122 — 리매치는 게임이 선언한다({@code GameDefinition.supportsRematch()}). 리매치가 없는 게임은
 * 정상 종료 때 FINISHED 로 가므로 보통은 이 경로에 오지 않는다. 그래도 IN_GAME 이면서 매치가 끝난
 * 방(배포 전에 끝난 방, FINISHED 전이 실패를 로그로만 넘긴 방)에서 조작된 POST /rematch 가 새 매치를
 * 시작하지 못하게 서버가 직접 막는다.
 */
class RoomServiceRematchTest {

    private final RoomRepository repository = mock(RoomRepository.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final DomainEventBus events = mock(DomainEventBus.class);
    private final RoomService service = new RoomService(repository, games, Clock.systemUTC(), events,
            mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    private static Room inGame(String gameType) {
        return new Room("r1", "방", gameType, 7L, RoomStatus.IN_GAME, 3, 3, List.of(7L, 8L, 9L),
                Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    @Test
    void a_game_without_rematch_support_cannot_be_rematched() {
        when(repository.findById("r1")).thenReturn(Optional.of(inGame("NO_REMATCH")));
        GameDefinition def = mock(GameDefinition.class);
        when(def.supportsRematch()).thenReturn(false);
        when(games.require("NO_REMATCH")).thenReturn(def);

        assertThatThrownBy(() -> service.rematch("r1", 7L))
                .isInstanceOf(GameNotInProgressException.class);

        verify(events, never()).publish(any());
    }
}
