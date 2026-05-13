package com.mirboard.domain.game.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.tichu.TichuGameDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameRegistryTest {

    @Test
    void catalog_sorts_available_before_coming_soon_then_by_displayName() {
        var tichu = new TichuGameDefinition();   // AVAILABLE, "티츄"
        var goSoon = stub("GO", "바둑", GameStatus.COMING_SOON);
        var chessSoon = stub("CHESS", "체스", GameStatus.COMING_SOON);

        var registry = new GameRegistry(List.of(goSoon, tichu, chessSoon));

        var ids = registry.catalog().stream().map(GameDefinition::id).toList();
        assertThat(ids).containsExactly("TICHU", "GO", "CHESS");  // 가 < 체 in Hangul order
    }

    @Test
    void catalog_excludes_disabled_games() {
        var enabled = stub("A", "A-game", GameStatus.AVAILABLE);
        var disabled = stub("Z", "Z-game", GameStatus.DISABLED);

        var registry = new GameRegistry(List.of(enabled, disabled));

        assertThat(registry.catalog()).extracting(GameDefinition::id).containsExactly("A");
    }

    @Test
    void available_filters_only_available_status() {
        var enabled = stub("A", "A", GameStatus.AVAILABLE);
        var soon = stub("B", "B", GameStatus.COMING_SOON);

        var registry = new GameRegistry(List.of(enabled, soon));

        assertThat(registry.available()).extracting(GameDefinition::id).containsExactly("A");
    }

    @Test
    void require_throws_for_unknown_id() {
        var registry = new GameRegistry(List.of(new TichuGameDefinition()));

        assertThatThrownBy(() -> registry.require("UNKNOWN"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void require_throws_for_disabled_id() {
        var disabled = stub("X", "X", GameStatus.DISABLED);
        var registry = new GameRegistry(List.of(disabled));

        assertThatThrownBy(() -> registry.require("X"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void duplicate_ids_fail_at_construction() {
        var a = stub("DUP", "A", GameStatus.AVAILABLE);
        var b = stub("DUP", "B", GameStatus.AVAILABLE);

        assertThatThrownBy(() -> new GameRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUP");
    }

    /**
     * 테스트 전용 {@link GameDefinition} stub. permits 절은 {@link TichuGameDefinition}
     * 만 허용하므로 본 헬퍼는 별도 final 클래스가 아니라 동적 위임으로 만들 수 없음 →
     * 테스트만 쓸 수 있도록 {@link FakeGameDefinition} 으로 분리.
     */
    private static GameDefinition stub(String id, String name, GameStatus status) {
        return new FakeGameDefinition(id, name, status);
    }
}
