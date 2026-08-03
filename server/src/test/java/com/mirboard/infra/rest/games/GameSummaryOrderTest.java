package com.mirboard.infra.rest.games;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-106 — `GameSummary.of` 의 `.sorted()` 계약. 카탈로그 응답의 `supportedRoomOptions`
 * 는 {@link RoomOption} <b>선언 순서</b>로 고정이고, 클라·문서가 그 순서를 계약으로 읽는다.
 *
 * <p>이 테스트가 따로 필요한 이유: 통합 테스트({@code GameCatalogIntegrationTest})는 실제
 * 게임 정의만 통과시키는데 둘 다 {@code EnumSet} 을 반환한다. {@code EnumSet} 은 명세상
 * <b>항상</b> 자연(선언) 순서로 순회하므로, {@code .sorted()} 를 지워도 통합 테스트는 전부
 * 통과한다 — 순서 보장이 구현의 부수 효과와 우연히 일치할 뿐이다. 순서가 흐트러진 집합을
 * 반환하는 정의를 넣어야 비로소 정렬 자체를 검증할 수 있다.
 *
 * <p>{@code GameSummary.of} 는 패키지 사설이라 같은 패키지의 이 테스트가 Spring 컨텍스트
 * 없이 직접 부른다.
 */
class GameSummaryOrderTest {

    /** 선언 순서와 어긋난 순회 순서를 갖는 집합을 반환하는 정의. */
    private record ShuffledOptionsGame(Set<RoomOption> options) implements GameDefinition {

        @Override
        public String id() {
            return "SHUFFLED";
        }

        @Override
        public String displayName() {
            return "순서 뒤집힌 게임";
        }

        @Override
        public String shortDescription() {
            return "";
        }

        @Override
        public int minPlayers() {
            return 2;
        }

        @Override
        public int maxPlayers() {
            return 4;
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
        public GameEngine newEngine(GameContext ctx) {
            throw new UnsupportedOperationException("카탈로그 매핑만 검증한다");
        }
    }

    @Test
    void room_options_are_emitted_in_declaration_order_regardless_of_set_iteration_order() {
        // LinkedHashSet 은 삽입 순서로 순회한다 — 여기서는 선언 순서의 정확한 역순.
        Set<RoomOption> reversed = new LinkedHashSet<>();
        reversed.add(RoomOption.BETTING);
        reversed.add(RoomOption.TEAMS);
        reversed.add(RoomOption.TARGET_SCORE);

        var summary = GameCatalogController.GameSummary.of(new ShuffledOptionsGame(reversed));

        assertThat(summary.supportedRoomOptions())
                .containsExactly(RoomOption.TARGET_SCORE, RoomOption.TEAMS, RoomOption.BETTING);
    }

    /** 순서 계약이 enum 선언 순서를 따른다는 것 자체를 못박는다 — 상수를 재배치하면 깨진다. */
    @Test
    void declaration_order_is_the_contract() {
        assertThat(RoomOption.values())
                .containsExactly(RoomOption.TARGET_SCORE, RoomOption.TEAMS, RoomOption.BETTING);
    }

    @Test
    void empty_options_map_to_empty_list() {
        var summary = GameCatalogController.GameSummary.of(new ShuffledOptionsGame(Set.of()));

        assertThat(summary.supportedRoomOptions()).isEmpty();
    }
}
