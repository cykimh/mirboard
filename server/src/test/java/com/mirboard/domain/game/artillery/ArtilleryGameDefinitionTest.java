package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 카탈로그 선언 (D-220·D-222·D-225): 2~4인, 턴 제한 '끔' 없음, 방 옵션은 모드(TEAM_MODE) 하나. */
class ArtilleryGameDefinitionTest {

    private final ArtilleryGameDefinition def = new ArtilleryGameDefinition(mock(ArtilleryStateStore.class),
            Clock.systemUTC(), e -> { }, GameStatus.DISABLED, 18_000);

    @Test
    void catalog_declaration() {
        assertThat(def.id()).isEqualTo("ARTILLERY");
        assertThat(def.displayName()).isEqualTo("씨앗포");
        assertThat(def.minPlayers()).isEqualTo(2);
        assertThat(def.maxPlayers()).isEqualTo(4);
        assertThat(def.status()).isEqualTo(GameStatus.DISABLED);
        assertThat(def.turnSecondsChoices()).containsExactly(20, 30, 45, 60).doesNotContain(0)
                .contains(def.defaultTurnSeconds());
        assertThat(def.supportedRoomOptions()).containsExactly(RoomOption.TEAM_MODE);
        assertThat(def.newEngine(new GameContext("r", List.of(1L, 2L)))).isInstanceOf(ArtilleryGameEngine.class);
    }

    /** D-225 — 정원은 두 모드 모두 4, 2:2 는 4명이 다 차야 시작. */
    @Test
    void team_mode_sets_players_to_start() {
        Map<RoomOption, String> solo = Map.of(RoomOption.TEAM_MODE, "SOLO");
        Map<RoomOption, String> teams = Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2");
        assertThat(def.capacityFor(solo)).isEqualTo(4);
        assertThat(def.capacityFor(teams)).isEqualTo(4);
        assertThat(def.playersToStart(solo)).isEqualTo(2);
        assertThat(def.playersToStart(Map.of())).isEqualTo(2);
        assertThat(def.playersToStart(teams)).isEqualTo(4);
        assertThat(ArtilleryGameDefinition.teamsOf(teams)).isTrue();
        assertThat(ArtilleryGameDefinition.teamsOf(solo)).isFalse();
    }
}
