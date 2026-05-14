package com.mirboard.domain.game.tichu.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WishTest {

    @Test
    void active_factory_creates_unfulfilled_wish() {
        var w = Wish.active(8);

        assertThat(w.rank()).isEqualTo(8);
        assertThat(w.fulfilled()).isFalse();
        assertThat(w.isActive()).isTrue();
    }

    @Test
    void fulfill_transitions_to_fulfilled() {
        var w = Wish.active(8).fulfill();

        assertThat(w.fulfilled()).isTrue();
        assertThat(w.isActive()).isFalse();
    }

    @Test
    void fulfilling_twice_is_idempotent() {
        var once = Wish.active(8).fulfill();
        var twice = once.fulfill();

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void rank_must_be_between_two_and_fourteen_inclusive() {
        assertThatThrownBy(() -> Wish.active(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Wish.active(15))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
