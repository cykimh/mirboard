package com.mirboard.domain.game.cloudroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** D-212 — 카탈로그 메타데이터, 기본 숨김(DISABLED), 엔진 팩토리. */
class CloudRouteGameDefinitionTest {

    private static CloudRouteGameDefinition definition(GameStatus status) {
        return new CloudRouteGameDefinition(mock(CloudRouteStateStore.class), event -> { }, status);
    }

    @Test
    void the_catalog_entry_is_a_two_to_four_player_game_without_room_options() {
        CloudRouteGameDefinition def = definition(GameStatus.AVAILABLE);
        assertThat(def.id()).isEqualTo("CLOUD_ROUTE");
        assertThat(def.displayName()).isEqualTo("구름항로");
        assertThat(def.minPlayers()).isEqualTo(2);
        assertThat(def.maxPlayers()).isEqualTo(4);
        assertThat(def.defaultPlayers()).isEqualTo(4);
        assertThat(def.supportedRoomOptions()).isEmpty();
        assertThat(def.turnSecondsChoices()).containsExactly(0, 15, 30, 60).contains(def.defaultTurnSeconds());
        assertThat(def.newEngine(new GameContext("r", List.of(1L, 2L)))).isInstanceOf(CloudRouteGameEngine.class);
    }

    /** 관제 규칙(2026-11-09) — 게임판이 붙기 전에는 설정 없이 띄워도 카탈로그에 보이지 않는다. */
    @Test
    void the_status_defaults_to_disabled() throws Exception {
        Parameter status = Arrays.stream(CloudRouteGameDefinition.class.getConstructors()[0].getParameters())
                .filter(p -> p.getType() == GameStatus.class).findFirst().orElseThrow();
        assertThat(status.getAnnotation(Value.class).value()).isEqualTo("${mirboard.cloudroute.status:DISABLED}");
        assertThat(definition(GameStatus.DISABLED).status()).isEqualTo(GameStatus.DISABLED);
    }

    @Test
    void an_empty_status_is_refused_with_a_clear_message() {
        assertThatThrownBy(() -> definition(null)).hasMessageContaining("MIRBOARD_CLOUDROUTE_STATUS");
    }
}
