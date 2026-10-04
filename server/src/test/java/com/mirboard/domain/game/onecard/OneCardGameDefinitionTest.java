package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D-128 — 카탈로그 메타데이터, 설정에서 오는 공개 상태·경쟁 창, 엔진 팩토리. */
class OneCardGameDefinitionTest {

    private static OneCardGameDefinition definition(GameStatus status, long window, long ownerMin, long ownerMax,
                                                    long catcherMin, long catcherMax) {
        return new OneCardGameDefinition(mock(OneCardStateStore.class), Clock.systemUTC(), event -> { },
                status, window, ownerMin, ownerMax, catcherMin, catcherMax);
    }

    private static OneCardGameDefinition defaults() {
        return definition(GameStatus.COMING_SOON, 3_000, 1_000, 2_500, 1_000, 2_500);
    }

    @Test
    void the_catalog_entry_is_a_two_to_six_player_game_without_room_options() {
        OneCardGameDefinition def = defaults();

        assertThat(def.id()).isEqualTo("ONE_CARD");
        assertThat(def.displayName()).isEqualTo("원카드");
        assertThat(def.minPlayers()).isEqualTo(2);
        assertThat(def.maxPlayers()).isEqualTo(6);
        assertThat(def.status()).isEqualTo(GameStatus.COMING_SOON);
        assertThat(def.supportedRoomOptions()).isEmpty();
        assertThat(def.supportsRematch()).isFalse();
    }

    @Test
    void status_and_race_timing_come_from_configuration_with_the_protocol_slot_count() {
        OneCardGameDefinition def = definition(GameStatus.AVAILABLE, 2_000, 100, 200, 300, 400);

        assertThat(def.status()).isEqualTo(GameStatus.AVAILABLE);
        assertThat(def.raceSettings()).isEqualTo(new RaceSettings(2_000, 100, 200, 300, 400, 8));
    }

    @Test
    void a_broken_race_configuration_fails_naming_the_condition() {
        assertThatThrownBy(() -> definition(GameStatus.COMING_SOON, 3_000, 2_600, 2_500, 1_000, 2_500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner reaction range");
    }

    @Test
    void the_factory_builds_a_port_adapter_for_the_room() {
        GameEngine engine = defaults().newEngine(new GameContext("room-9", List.of(1L, 2L)));

        assertThat(engine).isInstanceOf(OneCardGameEngine.class);
        assertThat(engine.context().roomId()).isEqualTo("room-9");
        assertThat(engine.actionType()).isEqualTo(OneCardAction.class);
    }
}
