package com.mirboard.domain.game.starsquare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.starsquare.persistence.StarSquareStateStore;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** D-285 — 카탈로그 메타데이터, 공개 상태 기본값(S4 전 DISABLED), 엔진 팩토리. */
class StarSquareGameDefinitionTest {

    private static StarSquareGameDefinition definition(GameStatus status) {
        return new StarSquareGameDefinition(mock(StarSquareStateStore.class), Clock.systemUTC(), event -> { }, status, 1.0);
    }

    @Test
    void the_catalog_entry_is_a_one_to_four_player_race_with_three_room_options() {
        StarSquareGameDefinition def = definition(GameStatus.AVAILABLE);
        assertThat(def.id()).isEqualTo("STAR_SQUARE");
        assertThat(def.displayName()).isEqualTo("별자리 수판");
        assertThat(def.minPlayers()).isEqualTo(1);
        assertThat(def.maxPlayers()).isEqualTo(4);
        assertThat(def.playerCounts()).containsExactly(1, 2, 3, 4);
        assertThat(def.supportedRoomOptions()).containsExactlyInAnyOrder(RoomOption.BOT_LEVEL, RoomOption.MATCH_LENGTH,
                RoomOption.DIFFICULTY);
        // 퍼즐 제한: 0 = 자동(난이도별), 기본 자동
        assertThat(def.defaultTurnSeconds()).isZero();
        assertThat(def.turnSecondsChoices()).containsExactly(0, 180, 300, 480, 600, 900, 1_200).contains(def.defaultTurnSeconds());
        assertThat(def.turnLimitLabel()).isEqualTo("퍼즐 제한");
        assertThat(def.turnLimitZeroLabel()).isEqualTo("자동");
        assertThat(def.newEngine(new GameContext("r", List.of(1L, 2L)))).isInstanceOf(StarSquareGameEngine.class);
        assertThat(RoomOption.DIFFICULTY.choices()).containsExactly("EASY", "NORMAL", "HARD");
        assertThat(RoomOption.DIFFICULTY.defaultValue()).isEqualTo("NORMAL");
    }

    /** S4 전까지 비공개 — 설정 없이 띄우면 카탈로그에도 없다. 두 기본값(@Value·application.yml)이 같아야 한다. */
    @Test
    void the_status_defaults_to_disabled_until_release() throws Exception {
        Parameter status = Arrays.stream(StarSquareGameDefinition.class.getConstructors()[0].getParameters())
                .filter(p -> p.getType() == GameStatus.class).findFirst().orElseThrow();
        assertThat(status.getAnnotation(Value.class).value()).isEqualTo("${mirboard.starsquare.status:DISABLED}");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        assertThat(yaml.getObject().getProperty("mirboard.starsquare.status"))
                .isEqualTo("${MIRBOARD_STAR_SQUARE_STATUS:DISABLED}");
        assertThat(yaml.getObject().getProperty("mirboard.starsquare.time-scale")).isEqualTo("1.0");
    }

    @Test
    void an_empty_status_is_refused_with_a_clear_message() {
        assertThatThrownBy(() -> definition(null)).hasMessageContaining("MIRBOARD_STAR_SQUARE_STATUS");
    }
}
