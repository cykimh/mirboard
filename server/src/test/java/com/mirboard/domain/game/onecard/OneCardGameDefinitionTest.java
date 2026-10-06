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

    /**
     * D-130 — 방 만들기의 처음 선택은 4명·턴 제한 30초다. 6석이 기본이면 친구 넷이 기본값으로 만든 방이 영원히 시작하지 않고
     * (정원은 만든 뒤 못 바꾼다) 게스트 첫 판이 봇 5명이 된다. 턴 제한이 꺼져 있으면 자리를 비운 한 명이 판을 무기한 멈추고
     * 결국 이긴다(버티기 — 사용자 결정으로 원카드만 30초). 30 은 방 만들기 모달의 선택지(0·30·60·90)다.
     */
    @Test
    void room_creation_starts_at_four_seats_and_a_thirty_second_turn_limit() {
        OneCardGameDefinition def = defaults();

        assertThat(def.defaultPlayers()).isEqualTo(4).isBetween(def.minPlayers(), def.maxPlayers());
        assertThat(def.defaultTurnSeconds()).isEqualTo(30);
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

    /**
     * 환경 변수가 "설정은 됐으나 빈 값"이면 스프링의 enum 변환이 null 을 준다. 그대로 두면 카탈로그 정렬에서 원인 모를
     * NPE 로 기동이 멈추므로, 설정 키를 밝히며 생성자에서 바로 실패한다({@code RaceSettings} 와 같은 방식).
     */
    @Test
    void a_missing_status_fails_at_startup_naming_the_setting() {
        assertThatThrownBy(() -> definition(null, 3_000, 1_000, 2_500, 1_000, 2_500))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mirboard.onecard.status");
    }

    @Test
    void the_factory_builds_a_port_adapter_for_the_room() {
        GameEngine engine = defaults().newEngine(new GameContext("room-9", List.of(1L, 2L)));

        assertThat(engine).isInstanceOf(OneCardGameEngine.class);
        assertThat(engine.context().roomId()).isEqualTo("room-9");
        assertThat(engine.actionType()).isEqualTo(OneCardAction.class);
    }
}
