package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 카탈로그 선언 (D-220·D-222): 2~4인, 턴 제한 '끔' 없음, 방 옵션 없음. */
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
        assertThat(def.supportedRoomOptions()).isEmpty();
        assertThat(def.newEngine(new GameContext("r", List.of(1L, 2L)))).isInstanceOf(ArtilleryGameEngine.class);
    }
}
