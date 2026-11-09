package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.core.TeamModeTestGame;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-176 E3 — 엔진은 게임이 선언한 맵 옵션 값만 받는다(선언한 것은 늘 값이 있다). */
class GameEngineProviderOptionsTest {

    private final TeamModeTestGame game = new TeamModeTestGame();
    private final GameRegistry games = mock(GameRegistry.class);
    private final GameEngineProvider provider = new GameEngineProvider(games);

    GameEngineProviderOptionsTest() {
        when(games.require(TeamModeTestGame.ID)).thenReturn(game);
    }

    private static Room room(Map<RoomOption, String> options) {
        return new Room("r1", "방", TeamModeTestGame.ID, 1L, RoomStatus.IN_GAME, 4, 4, List.of(1L, 2L, 3L, 4L),
                Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(3), 1000, 30, 0, Set.of(),
                Room.DEFAULT_RACE_BUTTON_SIZE, Room.DEFAULT_RACE_BUTTON_VISIBILITY, options);
    }

    @Test
    @DisplayName("선언한 옵션은 저장값, 저장 안 된 선언 옵션은 기본값, 선언 안 한 옵션은 빠진다")
    void passes_only_declared_options() {
        Room room = room(Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2", RoomOption.OMOK_RULE, "FREESTYLE"));

        assertThatThrownBy(() -> provider.forRoom(room)).isInstanceOf(UnsupportedOperationException.class);

        var ctx = game.lastContext.get();
        assertThat(ctx.options()).containsExactly(
                Map.entry(RoomOption.TEAM_MODE, "TEAMS_2V2"),
                Map.entry(RoomOption.BOT_LEVEL, "NORMAL"));
        // 예전 필드는 그대로 실린다.
        assertThat(ctx.playerIds()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(ctx.botSeats()).containsExactly(3);
        assertThat(ctx.targetScore()).isEqualTo(1000);
    }
}
