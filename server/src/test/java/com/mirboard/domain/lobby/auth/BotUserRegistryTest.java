package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BotUserRegistryTest {

    @Test
    void initializes_with_seed_bots_in_id_order() {
        List<User> bots = List.of(botUser(101L), botUser(102L), botUser(103L), botUser(104L));
        UserRepository repo = Mockito.mock(UserRepository.class);
        given(repo.findBots()).willReturn(bots);

        BotUserRegistry registry = new BotUserRegistry(repo);

        assertThat(registry.getBotIds()).containsExactly(101L, 102L, 103L, 104L);
    }

    @Test
    void isBot_recognizes_seed_ids_only() {
        BotUserRegistry registry = registryWith(11L, 12L, 13L, 14L);

        assertThat(registry.isBot(11L)).isTrue();
        assertThat(registry.isBot(14L)).isTrue();
        assertThat(registry.isBot(15L)).isFalse();
        assertThat(registry.isBot(0L)).isFalse();
    }

    @Test
    void takeBots_returns_requested_count_in_order() {
        BotUserRegistry registry = registryWith(11L, 12L, 13L, 14L);

        assertThat(registry.takeBots(0)).isEmpty();
        assertThat(registry.takeBots(3)).containsExactly(11L, 12L, 13L);
        assertThat(registry.takeBots(4)).containsExactly(11L, 12L, 13L, 14L);
    }

    @Test
    void takeBots_rejects_out_of_range_count() {
        BotUserRegistry registry = registryWith(11L, 12L, 13L, 14L);

        assertThatThrownBy(() -> registry.takeBots(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.takeBots(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throws_when_fewer_than_four_seed_bots() {
        List<User> bots = List.of(botUser(1L), botUser(2L));
        UserRepository repo = Mockito.mock(UserRepository.class);
        given(repo.findBots()).willReturn(bots);

        assertThatThrownBy(() -> new BotUserRegistry(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected at least 4");
    }

    @Test
    void caps_at_four_when_extra_bots_present() {
        // 운영 중 누군가 시드 봇을 5번째 INSERT 해도 4명만 사용.
        BotUserRegistry registry = registryWith(1L, 2L, 3L, 4L, 5L, 6L);

        assertThat(registry.getBotIds()).hasSize(4).containsExactly(1L, 2L, 3L, 4L);
        assertThat(registry.isBot(5L)).isFalse();
    }

    private static BotUserRegistry registryWith(long... ids) {
        List<User> bots = java.util.Arrays.stream(ids).mapToObj(BotUserRegistryTest::botUser).toList();
        UserRepository repo = Mockito.mock(UserRepository.class);
        given(repo.findBots()).willReturn(bots);
        return new BotUserRegistry(repo);
    }

    private static User botUser(long id) {
        User u = Mockito.mock(User.class);
        given(u.getId()).willReturn(id);
        return u;
    }
}
