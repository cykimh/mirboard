package com.mirboard.infra.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-122 — 매치가 끝났을 때 방을 FINISHED 로 넘길지는 <b>리매치 지원 여부</b>(게임 선언)와
 * 봇 유무로 정한다. 리매치(D-82)는 사람만의 테이블을 IN_GAME 으로 붙잡아 두는 장치라, 리매치를
 * 지원하지 않는 게임(스컬킹)이 그 분기를 타면 끝난 방이 IN_GAME 으로 남아 나가기가 탈주
 * 판정·좌석 당김으로 흘렀다.
 */
class MatchProgressServiceTest {

    private final RoomService roomService = mock(RoomService.class);
    private final MirboardMetrics metrics = mock(MirboardMetrics.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);

    private static GameDefinition game(String id, boolean rematch) {
        return new GameDefinition() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public String shortDescription() { return ""; }
            @Override public int minPlayers() { return 2; }
            @Override public int maxPlayers() { return 4; }
            @Override public GameStatus status() { return GameStatus.AVAILABLE; }
            @Override public boolean supportsRematch() { return rematch; }
            @Override public GameEngine newEngine(GameContext ctx) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private final MatchProgressService service = new MatchProgressService(
            roomService, metrics,
            new GameRegistry(List.of(game("REMATCHY", true), game("ONESHOT", false))));

    private static Room room(String gameType, List<Integer> botSeats) {
        return new Room("r1", "방", gameType, 1L, RoomStatus.IN_GAME, 4, 4,
                List.of(1L, 2L, 3L, 4L), Set.of(), TeamPolicy.SEQUENTIAL, 0L,
                !botSeats.isEmpty(), botSeats, 1000, 0, 0, Set.of());
    }

    private void matchEnds() {
        when(engine.advance(any(), any())).thenReturn(new GameEngine.Advance(true, true));
    }

    @Test
    void human_only_match_of_a_game_without_rematch_finishes_the_room() {
        matchEnds();

        service.advance(engine, room("ONESHOT", List.of()), state, new ArrayList<>());

        verify(roomService).markFinished("r1");
    }

    /** D-82 그대로 — 리매치 대기(IN_GAME 유지). */
    @Test
    void human_only_match_of_a_rematch_game_keeps_the_room_in_game() {
        matchEnds();

        service.advance(engine, room("REMATCHY", List.of()), state, new ArrayList<>());

        verify(roomService, never()).markFinished(any());
    }

    @Test
    void bot_match_finishes_the_room_even_if_the_game_supports_rematch() {
        matchEnds();

        service.advance(engine, room("REMATCHY", List.of(2, 3)), state, new ArrayList<>());

        verify(roomService).markFinished("r1");
    }

    @Test
    void a_round_that_does_not_end_the_match_never_touches_the_room() {
        when(engine.advance(any(), any())).thenReturn(new GameEngine.Advance(true, false));

        service.advance(engine, room("ONESHOT", List.of()), state, new ArrayList<>());

        verify(roomService, never()).markFinished(any());
    }
}
