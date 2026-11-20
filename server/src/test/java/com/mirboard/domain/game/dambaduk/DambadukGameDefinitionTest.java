package com.mirboard.domain.game.dambaduk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** D-278 — 카탈로그 메타데이터, 공개 상태 기본값(S4 전까지 DISABLED), 엔진 팩토리. */
class DambadukGameDefinitionTest {

    private static DambadukGameDefinition definition(GameStatus status) {
        return new DambadukGameDefinition(mock(DambadukStateStore.class), event -> { }, status);
    }

    @Test
    void the_catalog_entry_is_a_two_to_three_player_game_with_only_a_bot_level_option() {
        DambadukGameDefinition def = definition(GameStatus.AVAILABLE);
        assertThat(def.id()).isEqualTo("DAMBADUK");
        assertThat(def.displayName()).isEqualTo("담바둑");
        assertThat(def.minPlayers()).isEqualTo(2);
        assertThat(def.maxPlayers()).isEqualTo(3);
        assertThat(def.playerCounts()).containsExactly(2, 3);
        assertThat(def.supportedRoomOptions()).containsExactly(RoomOption.BOT_LEVEL);
        assertThat(def.turnSecondsChoices()).containsExactly(0, 30, 60, 90);
        assertThat(def.defaultTurnSeconds()).isEqualTo(60);
        assertThat(def.newEngine(new GameContext("r", List.of(1L, 2L)))).isInstanceOf(DambadukGameEngine.class);
    }

    /** S4 공개 전까지 감춘다 — 설정 없이 띄우면 카탈로그에도 없다. 두 기본값(@Value·application.yml)이 같아야 한다. */
    @Test
    void the_status_defaults_to_disabled_in_both_places() {
        Parameter status = Arrays.stream(DambadukGameDefinition.class.getConstructors()[0].getParameters())
                .filter(p -> p.getType() == GameStatus.class).findFirst().orElseThrow();
        assertThat(status.getAnnotation(Value.class).value()).isEqualTo("${mirboard.dambaduk.status:DISABLED}");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        assertThat(yaml.getObject().getProperty("mirboard.dambaduk.status"))
                .isEqualTo("${MIRBOARD_DAMBADUK_STATUS:DISABLED}");
        assertThat(definition(GameStatus.AVAILABLE).status()).isEqualTo(GameStatus.AVAILABLE);
    }

    @Test
    void an_empty_status_is_refused_with_a_clear_message() {
        assertThatThrownBy(() -> definition(null)).hasMessageContaining("MIRBOARD_DAMBADUK_STATUS");
    }

    @Test
    void settings_read_the_bot_level_and_fall_back_to_normal() {
        assertThat(DambadukSettings.from(java.util.Map.of(RoomOption.BOT_LEVEL, "HARD")).botLevel().name())
                .isEqualTo("HARD");
        assertThat(DambadukSettings.from(java.util.Map.of(RoomOption.BOT_LEVEL, "??")).botLevel().name())
                .isEqualTo("NORMAL");
        assertThat(DambadukSettings.from(null)).isEqualTo(DambadukSettings.DEFAULT);
    }
}
