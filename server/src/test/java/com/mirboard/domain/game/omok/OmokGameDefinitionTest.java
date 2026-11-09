package com.mirboard.domain.game.omok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** 카탈로그·방 설정·정원 (D-175·D-176). */
class OmokGameDefinitionTest {

    private final OmokGameDefinition def = new OmokGameDefinition(mock(OmokStateStore.class),
            mock(ApplicationEventPublisher.class), GameStatus.COMING_SOON);

    @Test
    void catalog_metadata() {
        assertThat(def.id()).isEqualTo("OMOK");
        assertThat(def.displayName()).isEqualTo("오목");
        assertThat(def.minPlayers()).isEqualTo(2);
        assertThat(def.maxPlayers()).isEqualTo(4);
        assertThat(def.status()).isEqualTo(GameStatus.COMING_SOON);
        assertThat(def.supportedRoomOptions())
                .containsExactlyInAnyOrder(RoomOption.TEAM_MODE, RoomOption.OMOK_RULE, RoomOption.BOT_LEVEL);
    }

    @Test
    void capacity_and_start_count_follow_the_mode() {
        assertThat(def.capacityFor(Map.of(RoomOption.TEAM_MODE, "SOLO"))).isEqualTo(2);
        assertThat(def.capacityFor(Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2"))).isEqualTo(4);
        assertThat(def.capacityFor(Map.of())).isEqualTo(2);
        assertThat(def.capacityFor(Map.of(RoomOption.TEAM_MODE, "bogus"))).isEqualTo(2);
        assertThat(def.playersToStart(Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2"))).isEqualTo(4);
        assertThat(def.playersToStart(Map.of(RoomOption.TEAM_MODE, "SOLO"))).isEqualTo(2);
        assertThat(def.defaultPlayers()).isEqualTo(def.capacityFor(Map.of()));
    }

    @Test
    void turn_defaults() {
        assertThat(def.defaultTurnSeconds()).isEqualTo(30);
        assertThat(def.turnSecondsChoices()).containsExactly(0, 15, 30, 60).contains(def.defaultTurnSeconds());
    }

    @Test
    void the_new_engine_reads_the_room_options() {
        var engine = def.newEngine(new GameContext("r", List.of(1L, 2L), 0, 0, List.of(),
                Map.of(RoomOption.BOT_LEVEL, "HARD")));
        assertThat(engine).isInstanceOf(OmokGameEngine.class);
        assertThat(engine.context().option(RoomOption.BOT_LEVEL)).isEqualTo("HARD");
    }

    @Test
    void an_empty_status_is_rejected_with_a_clear_message() {
        assertThatThrownBy(() -> new OmokGameDefinition(mock(OmokStateStore.class), mock(ApplicationEventPublisher.class),
                null)).hasMessageContaining("MIRBOARD_OMOK_STATUS");
    }

    @Test
    void settings_fall_back_to_defaults() {
        assertThat(OmokSettings.from(null)).isEqualTo(OmokSettings.DEFAULT);
        assertThat(OmokSettings.from(Map.of(RoomOption.OMOK_RULE, "FREESTYLE", RoomOption.TEAM_MODE, "TEAMS_2V2",
                RoomOption.BOT_LEVEL, "EASY")))
                .isEqualTo(new OmokSettings(OmokMode.TEAMS_2V2, OmokRule.FREESTYLE,
                        com.mirboard.domain.game.omok.bot.BotLevel.EASY));
    }
}
