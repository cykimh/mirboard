package com.mirboard.domain.game.skullking.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 진행 중 트릭의 좌석 산술 + 파생 리드 수트. */
class TrickStateTest {

    private static PlayedCard green(int seat, int rank) {
        return PlayedCard.of(seat, SkullCard.of(SkullSuit.GREEN, rank));
    }

    @Test
    void a_fresh_trick_is_at_the_lead_seat() {
        TrickState trick = TrickState.lead(2);

        assertThat(trick.isLead()).isTrue();
        assertThat(trick.played()).isEmpty();
        assertThat(trick.currentTurnSeat(4)).isEqualTo(2);
        assertThat(trick.leadSuit()).isEmpty();
    }

    @Test
    void turn_advances_clockwise_and_wraps() {
        TrickState trick = TrickState.lead(3);

        assertThat(trick.currentTurnSeat(4)).isEqualTo(3);
        trick = trick.with(green(3, 5));
        assertThat(trick.currentTurnSeat(4)).isZero();
        trick = trick.with(green(0, 6));
        assertThat(trick.currentTurnSeat(4)).isEqualTo(1);
    }

    @Test
    void a_full_trick_reports_completion_and_no_current_turn() {
        TrickState trick = TrickState.lead(0)
                .with(green(0, 2))
                .with(green(1, 3));

        assertThat(trick.isComplete(2)).isTrue();
        assertThat(trick.currentTurnSeat(2)).isEqualTo(-1);
    }

    @Test
    void lead_suit_is_derived_from_the_played_sequence() {
        TrickState trick = TrickState.lead(0).with(green(0, 9));

        assertThat(trick.leadSuit()).contains(SkullSuit.GREEN);
    }

    @Test
    void two_seat_table_wraps_correctly() {
        TrickState trick = TrickState.lead(1);

        assertThat(trick.currentTurnSeat(2)).isEqualTo(1);
        assertThat(trick.with(green(1, 4)).currentTurnSeat(2)).isZero();
    }

    @Test
    void eight_seat_table_wraps_correctly() {
        TrickState trick = TrickState.lead(6);

        assertThat(trick.currentTurnSeat(8)).isEqualTo(6);
        trick = trick.with(green(6, 1)).with(green(7, 2));
        assertThat(trick.currentTurnSeat(8)).isZero();
    }

    @Test
    void played_list_is_immutable() {
        TrickState trick = TrickState.lead(0).with(green(0, 5));

        assertThatThrownBy(() -> trick.played().add(green(1, 6)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void with_returns_a_new_state_leaving_the_original_untouched() {
        TrickState first = TrickState.lead(0);
        TrickState second = first.with(green(0, 5));

        assertThat(first.played()).isEmpty();
        assertThat(second.played()).hasSize(1);
        assertThat(second.leadSeat()).isEqualTo(first.leadSeat());
    }

    @Test
    void player_state_removes_only_one_copy_of_a_duplicated_card() {
        PlayerState player = PlayerState.initial(0,
                List.of(SkullCard.pirate(), SkullCard.pirate(), SkullCard.escape()));

        PlayerState after = player.withoutCard(SkullCard.pirate());

        assertThat(after.hand()).containsExactly(SkullCard.pirate(), SkullCard.escape());
    }

    @Test
    void removing_a_card_not_held_is_rejected() {
        PlayerState player = PlayerState.initial(0, List.of(SkullCard.escape()));

        assertThatThrownBy(() -> player.withoutCard(SkullCard.skullKing()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not hold");
    }

    @Test
    void bid_sentinel_distinguishes_unset_from_zero() {
        PlayerState player = PlayerState.initial(0, List.of(SkullCard.escape()));

        assertThat(player.hasBid()).isFalse();
        assertThat(player.withBid(0).hasBid())
                .as("0 은 유효한 예측이라 미제출과 구분돼야 한다")
                .isTrue();
    }
}
