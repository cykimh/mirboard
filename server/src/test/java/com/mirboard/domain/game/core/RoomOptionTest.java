package com.mirboard.domain.game.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-176 — 맵 방 옵션의 허용값·기본값과 {@link RoomOption#resolve}(선언한 옵션만, 늘 값이 있다). */
class RoomOptionTest {

    @Test
    @DisplayName("새 옵션 셋은 값을 갖고, 예전 다섯은 자기 필드라 맵 옵션이 아니다")
    void valued_options() {
        assertThat(RoomOption.TEAM_MODE.choices()).containsExactly("SOLO", "TEAMS_2V2");
        assertThat(RoomOption.TEAM_MODE.defaultValue()).isEqualTo("SOLO");
        assertThat(RoomOption.OMOK_RULE.choices()).containsExactly("RENJU", "DOUBLE_THREE_ONLY", "FREESTYLE");
        assertThat(RoomOption.OMOK_RULE.defaultValue()).isEqualTo("RENJU");
        assertThat(RoomOption.BOT_LEVEL.choices()).containsExactly("EASY", "NORMAL", "HARD");
        assertThat(RoomOption.BOT_LEVEL.defaultValue()).isEqualTo("NORMAL");
        for (RoomOption legacy : EnumSet.of(RoomOption.TARGET_SCORE, RoomOption.TEAMS, RoomOption.BETTING,
                RoomOption.RACE_BUTTON_SIZE, RoomOption.RACE_BUTTON_VISIBILITY)) {
            assertThat(legacy.valued()).as(legacy.name()).isFalse();
            assertThat(legacy.accepts("anything")).isFalse();
        }
    }

    @Test
    @DisplayName("resolve — 선언한 맵 옵션은 저장값(허용값일 때) 아니면 기본값, 선언하지 않은 옵션은 빠진다")
    void resolve_keeps_only_declared_and_fills_defaults() {
        var stored = Map.of(
                RoomOption.TEAM_MODE, "TEAMS_2V2",
                RoomOption.BOT_LEVEL, "IMPOSSIBLE",       // 허용값 밖 → 기본
                RoomOption.OMOK_RULE, "FREESTYLE");        // 선언 안 함 → 빠짐

        var resolved = RoomOption.resolve(
                EnumSet.of(RoomOption.TEAM_MODE, RoomOption.BOT_LEVEL, RoomOption.TARGET_SCORE), stored);

        assertThat(resolved).containsExactly(
                Map.entry(RoomOption.TEAM_MODE, "TEAMS_2V2"),
                Map.entry(RoomOption.BOT_LEVEL, "NORMAL"));
    }

    @Test
    @DisplayName("resolve — 맵 옵션을 선언하지 않은 게임(세 게임)은 빈 맵")
    void resolve_empty_for_games_without_valued_options() {
        assertThat(RoomOption.resolve(EnumSet.of(RoomOption.TARGET_SCORE, RoomOption.TEAMS, RoomOption.BETTING),
                Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2"))).isEmpty();
        assertThat(RoomOption.resolve(EnumSet.noneOf(RoomOption.class), null)).isEmpty();
    }

    @Test
    @DisplayName("해시 필드 opt:<NAME> 왕복 — 모르는 이름·맵 옵션 아닌 이름·다른 필드는 null")
    void hash_field_round_trip() {
        assertThat(RoomOption.TEAM_MODE.hashField()).isEqualTo("opt:TEAM_MODE");
        assertThat(RoomOption.fromHashField("opt:TEAM_MODE")).isEqualTo(RoomOption.TEAM_MODE);
        assertThat(RoomOption.fromHashField("opt:NOPE")).isNull();
        assertThat(RoomOption.fromHashField("opt:TARGET_SCORE")).isNull();
        assertThat(RoomOption.fromHashField("capacity")).isNull();
    }

    @Test
    @DisplayName("GameDefinition 기본 — 정원 = maxPlayers, 시작 인원 = minPlayers (세 게임 동작 그대로)")
    void definition_defaults() {
        var def = new FakeGameDefinition("X", "x", GameStatus.AVAILABLE);
        assertThat(def.capacityFor(Map.of())).isEqualTo(4);
        assertThat(def.playersToStart(Map.of())).isEqualTo(2);
    }

    @Test
    @DisplayName("GameContext — 예전 생성자는 빈 옵션, option() 은 없는 옵션에 null")
    void context_options() {
        var legacy = new GameContext("r", java.util.List.of(1L, 2L), 0, 0, java.util.List.of());
        assertThat(legacy.options()).isEmpty();
        var withOptions = new GameContext("r", java.util.List.of(1L, 2L), 0, 0, java.util.List.of(),
                Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2"));
        assertThat(withOptions.option(RoomOption.TEAM_MODE)).isEqualTo("TEAMS_2V2");
        assertThat(withOptions.option(RoomOption.BOT_LEVEL)).isNull();
    }
}
