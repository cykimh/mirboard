package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** D-128 — 경쟁 창 설정은 운영 설정에서 오므로, 틀린 값은 기동 때 어느 조건인지 말하며 실패해야 한다. */
class RaceSettingsTest {

    @Test
    void the_default_is_a_three_second_window_with_bots_reacting_in_one_to_two_and_a_half_seconds() {
        assertThat(RaceSettings.DEFAULT).isEqualTo(new RaceSettings(3_000, 1_000, 2_500, 1_000, 2_500, 8));
    }

    @Test
    void each_broken_condition_is_named_in_the_error() {
        assertThatThrownBy(() -> new RaceSettings(0, 1_000, 2_500, 1_000, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windowMillis");
        assertThatThrownBy(() -> new RaceSettings(3_000, 1_000, 2_500, 1_000, 2_500, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slotCount");
        assertThatThrownBy(() -> new RaceSettings(3_000, -1, 2_500, 1_000, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner reaction range");
        assertThatThrownBy(() -> new RaceSettings(3_000, 2_600, 2_500, 1_000, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner reaction range");
        assertThatThrownBy(() -> new RaceSettings(3_000, 1_000, 2_500, -1, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catcher reaction range");
        assertThatThrownBy(() -> new RaceSettings(3_000, 1_000, 2_500, 2_600, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catcher reaction range");
    }

    @Test
    void equal_bounds_mean_a_fixed_reaction_time() {
        assertThatNoException().isThrownBy(() -> new RaceSettings(3_000, 1_200, 1_200, 1_500, 1_500, 8));
    }
}
