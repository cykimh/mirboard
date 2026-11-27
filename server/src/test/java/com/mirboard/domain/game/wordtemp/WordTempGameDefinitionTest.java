package com.mirboard.domain.game.wordtemp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.scoring.LiveHintScorer;
import com.mirboard.domain.game.wordtemp.words.WordTempWords;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** D-310·D-316 — 카탈로그 메타데이터, 공개 상태 기본값(S4 전까지 DISABLED), 방 설정 해석, 엔진 팩토리. */
class WordTempGameDefinitionTest {

    private static final WordTempWords WORDS = new WordTempWords();

    private static WordTempGameDefinition definition(GameStatus status) {
        return new WordTempGameDefinition(mock(WordTempStateStore.class), WORDS, LiveHintScorer.OFF, Clock.systemUTC(),
                event -> { }, status, 60, 1.0);
    }

    @Test
    void the_catalog_entry_is_a_three_to_six_player_game_with_two_existing_room_options() {
        WordTempGameDefinition def = definition(GameStatus.AVAILABLE);
        assertThat(def.id()).isEqualTo("WORD_TEMP");
        assertThat(def.displayName()).isEqualTo("낱말 온도");
        assertThat(def.minPlayers()).isEqualTo(3);
        assertThat(def.maxPlayers()).isEqualTo(6);
        assertThat(def.defaultPlayers()).isEqualTo(4);
        assertThat(def.playerCounts()).containsExactly(3, 4, 5, 6);
        assertThat(def.supportedRoomOptions()).containsExactlyInAnyOrder(RoomOption.MATCH_LENGTH, RoomOption.BOT_LEVEL);
        // 힌트 제한: 끔이 없다
        assertThat(def.defaultTurnSeconds()).isEqualTo(90);
        assertThat(def.turnSecondsChoices()).containsExactly(60, 90, 120, 180).contains(def.defaultTurnSeconds());
        assertThat(def.turnLimitLabel()).isEqualTo("힌트 제한");
        assertThat(def.newEngine(new GameContext("r", List.of(1L, 2L, 3L)))).isInstanceOf(WordTempGameEngine.class);
    }

    /** S4 공개 전 — 설정 없이 띄우면 카탈로그에 없다. 두 기본값(@Value·application.yml)이 같아야 한다. 실시간 채점은 기본 꺼짐. */
    @Test
    void the_status_defaults_to_disabled_and_live_scoring_defaults_to_off() {
        // 스프링이 쓰는 생성자(@Autowired — D-321 로 생성자가 둘이 됐다)
        java.lang.reflect.Constructor<?> springCtor = Arrays.stream(WordTempGameDefinition.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .findFirst().orElseThrow();
        Parameter status = Arrays.stream(springCtor.getParameters())
                .filter(p -> p.getType() == GameStatus.class).findFirst().orElseThrow();
        assertThat(status.getAnnotation(Value.class).value()).isEqualTo("${mirboard.wordtemp.status:DISABLED}");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertThat(props.getProperty("mirboard.wordtemp.status")).isEqualTo("${MIRBOARD_WORD_TEMP_STATUS:DISABLED}");
        assertThat(props.getProperty("mirboard.wordtemp.time-scale")).isEqualTo("1.0");
        assertThat(props.getProperty("mirboard.wordtemp.model")).isEqualTo("${MIRBOARD_WORD_TEMP_MODEL:claude-sonnet-5-5}");
        assertThat(props.getProperty("mirboard.wordtemp.live-scoring.enabled"))
                .isEqualTo("${MIRBOARD_WORD_TEMP_LIVE_SCORING:false}");
        assertThat(props.getProperty("mirboard.wordtemp.live-scoring.api-key")).isEqualTo("${ANTHROPIC_API_KEY:}");
        assertThat(props.getProperty("mirboard.wordtemp.live-scoring.prompt-version")).isEqualTo("score-v3");
        // 실시간 기준문 자원이 있다
        assertThat(new ClassPathResource("wordtemp/score-v3.md").exists()).isTrue();
    }

    @Test
    void an_empty_status_fails_fast() {
        assertThatThrownBy(() -> definition(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MIRBOARD_WORD_TEMP_STATUS");
    }

    @Test
    void room_settings_become_the_match_config() {
        WordTempGameDefinition def = definition(GameStatus.AVAILABLE);
        GameContext ctx = new GameContext("r", List.of(1L, 2L, 3L, 4L), 0, 0, List.of(1, 3),
                Map.of(RoomOption.MATCH_LENGTH, "LONG", RoomOption.BOT_LEVEL, "HARD"), 120);
        WordTempConfig config = def.config(ctx);
        assertThat(config.seatCount()).isEqualTo(4);
        assertThat(config.bots()).containsExactlyInAnyOrder(1, 3);
        assertThat(config.rounds()).isEqualTo(3);
        assertThat(config.hintSeconds()).isEqualTo(120);
        assertThat(config.botLevel()).isEqualTo(WordTempBotLevel.HARD);
        assertThat(config.liveCap()).isEqualTo(60);
        assertThat(config.blockedWords()).isEqualTo(WORDS.blockedWords());
        assertThat(config.tuning()).isEqualTo(WordTempTuning.DEFAULT);

        assertThat(WordTempSettings.roundsFor("SHORT")).isEqualTo(1);
        assertThat(WordTempSettings.roundsFor("NORMAL")).isEqualTo(2);
        assertThat(WordTempSettings.roundsFor(null)).isEqualTo(2);
        // 턴 제한 0(끔)은 이 게임에 없다 — 기본 90초로 읽는다
        assertThat(def.config(new GameContext("r", List.of(1L, 2L, 3L))).hintSeconds()).isEqualTo(90);
    }

    @Test
    void the_time_scale_shrinks_phase_lengths_but_not_the_hint_limit_or_the_scoring_wait() {
        WordTempTuning scaled = WordTempTuning.DEFAULT.scaled(0.01);
        assertThat(scaled.selectMs()).isEqualTo(200);
        assertThat(scaled.guessMs()).isEqualTo(300);
        assertThat(scaled.resultMs()).isEqualTo(50);
        assertThat(scaled.turnEndMs()).isEqualTo(60);
        assertThat(scaled.botHintMinMs()).isEqualTo(40);
        assertThat(scaled.liveWaitMs()).isEqualTo(8_000);
        assertThat(scaled.floors()).isEqualTo(WordTempTuning.DEFAULT.floors());
        assertThat(WordTempTuning.DEFAULT.scaled(1.0)).isSameAs(WordTempTuning.DEFAULT);
    }

    @org.junit.jupiter.api.Test
    void production_floors_come_from_config_and_fall_back_to_the_rule_reference() {
        org.assertj.core.api.Assertions.assertThat(WordTempGameDefinition.parseFloors("50, 55,60,65,70").floors())
                .containsExactly(50, 55, 60, 65, 70);
        org.assertj.core.api.Assertions.assertThat(WordTempGameDefinition.parseFloors("50,60,70").maxAttempts()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(WordTempGameDefinition.parseFloors("")).isEqualTo(WordTempTuning.DEFAULT);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WordTempGameDefinition.parseFloors("50,120"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
