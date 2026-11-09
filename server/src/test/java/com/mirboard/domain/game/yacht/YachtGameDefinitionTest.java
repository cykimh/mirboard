package com.mirboard.domain.game.yacht;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 카탈로그 선언 — D-181(1~4인)·D-182(차례 45초)·방 옵션 없음(D-106 옵트인). */
class YachtGameDefinitionTest {

    private final YachtGameDefinition def = new YachtGameDefinition(mock(YachtStateStore.class), Clock.systemUTC(),
            event -> { }, GameStatus.COMING_SOON);

    @Test
    void catalog_declaration() {
        assertThat(def.id()).isEqualTo("YACHT");
        assertThat(def.minPlayers()).isEqualTo(1);
        assertThat(def.maxPlayers()).isEqualTo(4);
        assertThat(def.defaultPlayers()).isEqualTo(4);
        assertThat(def.defaultTurnSeconds()).isEqualTo(45);
        assertThat(def.turnSecondsChoices()).containsExactly(0, 20, 30, 45, 60).contains(def.defaultTurnSeconds());
        assertThat(def.supportedRoomOptions()).isEmpty();
        assertThat(def.status()).isEqualTo(GameStatus.COMING_SOON);
        assertThat(def.displayName()).doesNotContainIgnoringCase("yahtzee");
    }

    @Test
    void new_engine_is_the_yacht_adapter() {
        assertThat(def.newEngine(new GameContext("r", List.of(1L)))).isInstanceOf(YachtGameEngine.class);
    }

    @Test
    void an_empty_status_fails_fast() {
        assertThatThrownBy(() -> new YachtGameDefinition(mock(YachtStateStore.class), Clock.systemUTC(), e -> { },
                null)).isInstanceOf(NullPointerException.class).hasMessageContaining("MIRBOARD_YACHT_STATUS");
    }
}
