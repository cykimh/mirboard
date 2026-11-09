package com.mirboard.domain.game.yacht.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** `docs/rules-yacht.md` §5.1 — 소계·보너스·총점. */
class ScorecardTest {

    @Test
    void bonus_starts_exactly_at_63() {
        Scorecard s62 = Scorecard.EMPTY.with(Category.SIXES, 30).with(Category.FIVES, 20).with(Category.FOURS, 12);
        assertThat(s62.upperSubtotal()).isEqualTo(62);
        assertThat(s62.bonus()).isZero();
        assertThat(s62.total()).isEqualTo(62);

        Scorecard s63 = s62.with(Category.ONES, 1);
        assertThat(s63.upperSubtotal()).isEqualTo(63);
        assertThat(s63.bonus()).isEqualTo(35);
        assertThat(s63.total()).isEqualTo(98);
    }

    @Test
    void lower_categories_do_not_count_toward_the_bonus() {
        Scorecard s = Scorecard.EMPTY.with(Category.CHOICE, 30).with(Category.YACHT, 50).with(Category.SIXES, 30);
        assertThat(s.upperSubtotal()).isEqualTo(30);
        assertThat(s.bonus()).isZero();
        assertThat(s.total()).isEqualTo(110);
    }

    @Test
    void the_best_possible_card_is_325() {
        Scorecard best = Scorecard.EMPTY
                .with(Category.ONES, 5).with(Category.TWOS, 10).with(Category.THREES, 15)
                .with(Category.FOURS, 20).with(Category.FIVES, 25).with(Category.SIXES, 30)
                .with(Category.CHOICE, 30).with(Category.FOUR_OF_A_KIND, 30).with(Category.FULL_HOUSE, 30)
                .with(Category.SMALL_STRAIGHT, 15).with(Category.LARGE_STRAIGHT, 30).with(Category.YACHT, 50);
        assertThat(best.isFull()).isTrue();
        assertThat(best.total()).isEqualTo(325);
    }

    @Test
    void a_category_is_written_once() {
        Scorecard s = Scorecard.EMPTY.with(Category.YACHT, 0);
        assertThat(s.filled(Category.YACHT)).isTrue();
        assertThat(s.filledCount()).isEqualTo(1);
        assertThatThrownBy(() -> s.with(Category.YACHT, 50)).isInstanceOf(IllegalStateException.class);
    }
}
