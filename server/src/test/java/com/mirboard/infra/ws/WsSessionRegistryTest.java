package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WsSessionRegistryTest {

    private final WsSessionRegistry registry = new WsSessionRegistry();

    @Test
    void register_then_remove_returns_session_info() {
        registry.register("s1", 42L, "room-A");

        var removed = registry.remove("s1");

        assertThat(removed).isPresent();
        assertThat(removed.get().userId()).isEqualTo(42L);
        assertThat(removed.get().roomId()).isEqualTo("room-A");
        // 두 번째 remove 는 비어 있어야 한다.
        assertThat(registry.remove("s1")).isEmpty();
    }

    @Test
    void hasLiveSession_true_only_for_matching_user_and_room() {
        registry.register("s1", 42L, "room-A");

        assertThat(registry.hasLiveSession(42L, "room-A")).isTrue();
        assertThat(registry.hasLiveSession(42L, "room-B")).isFalse();
        assertThat(registry.hasLiveSession(99L, "room-A")).isFalse();
    }

    @Test
    void second_session_keeps_user_live_after_first_drops() {
        registry.register("s1", 7L, "room-X");
        registry.register("s2", 7L, "room-X"); // 재접속(새 세션)

        registry.remove("s1"); // 옛 세션 끊김

        assertThat(registry.hasLiveSession(7L, "room-X")).isTrue();
    }
}
