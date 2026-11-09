package com.mirboard.domain.game.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/**
 * D-196 — {@link GameDefinition#playerCounts()} 기본 구현. 선언한 맵 옵션의 값 조합마다 시작 인원..정원을 모은 합집합이다
 * (게임 이름을 모른다). 맵 옵션이 없으면 min..max 그대로라 기존 게임은 바뀌지 않는다.
 */
class GameDefinitionPlayerCountsTest {

    @Test
    void without_valued_options_is_min_to_max() {
        // FakeGameDefinition 은 2~4인, 방 옵션 없음.
        var def = new FakeGameDefinition("FAKE", "가짜", GameStatus.AVAILABLE);
        assertThat(def.playerCounts()).containsExactly(2, 3, 4);
    }

    @Test
    void mode_that_fixes_capacity_skips_the_middle() {
        // TEAM_MODE 1:1 → 정확히 2, 2:2 → 정확히 4 (BOT_LEVEL 은 정원과 무관 — 조합이 늘어도 결과는 같다).
        assertThat(new TeamModeTestGame().playerCounts()).containsExactly(2, 4);
    }

    @Test
    void ranges_per_combination_are_merged_in_order() {
        // 1:1 은 2명만, 2:2 는 3~5명(가상) — 합집합 2·3·4·5, 오름차순·중복 없음.
        var def = new OptionGame(2, 5, EnumSet.of(RoomOption.TEAM_MODE, RoomOption.BOT_LEVEL),
                o -> teams(o) ? 3 : 2, o -> teams(o) ? 5 : 2);
        assertThat(def.playerCounts()).containsExactly(2, 3, 4, 5);
    }

    @Test
    void options_without_values_do_not_multiply() {
        // 값이 없는 옵션(TARGET_SCORE 등)은 조합에 들지 않는다 — 기본 min..max.
        var def = new OptionGame(2, 4, EnumSet.of(RoomOption.TARGET_SCORE, RoomOption.TEAMS),
                o -> 2, o -> 4);
        assertThat(def.playerCounts()).containsExactly(2, 3, 4);
    }

    private static boolean teams(Map<RoomOption, String> options) {
        return "TEAMS_2V2".equals(options.get(RoomOption.TEAM_MODE));
    }

    /** 옵션·정원 규칙을 주입하는 테스트 전용 정의. */
    private record OptionGame(int min, int max, Set<RoomOption> options,
                              ToIntFunction<Map<RoomOption, String>> start,
                              ToIntFunction<Map<RoomOption, String>> capacity) implements GameDefinition {

        @Override
        public String id() {
            return "OPTION_GAME";
        }

        @Override
        public String displayName() {
            return "옵션 게임";
        }

        @Override
        public String shortDescription() {
            return "";
        }

        @Override
        public int minPlayers() {
            return min;
        }

        @Override
        public int maxPlayers() {
            return max;
        }

        @Override
        public GameStatus status() {
            return GameStatus.AVAILABLE;
        }

        @Override
        public Set<RoomOption> supportedRoomOptions() {
            return options;
        }

        @Override
        public int capacityFor(Map<RoomOption, String> o) {
            return capacity.applyAsInt(o);
        }

        @Override
        public int playersToStart(Map<RoomOption, String> o) {
            return start.applyAsInt(o);
        }

        @Override
        public GameEngine newEngine(GameContext ctx) {
            throw new UnsupportedOperationException();
        }
    }
}
