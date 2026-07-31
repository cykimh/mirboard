package com.mirboard.domain.game.skullking.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 매치 누적 상태 (§3, §12, §13-⑮⑰). */
class SkullKingMatchStateTest {

    @Test
    void initial_state_starts_at_round_one_with_zero_scores() {
        SkullKingMatchState state = SkullKingMatchState.initial(4, 2);

        assertThat(state.roundNumber()).isEqualTo(1);
        assertThat(state.startSeat()).isEqualTo(2);
        assertThat(state.cumulativeScores()).containsOnlyKeys(0, 1, 2, 3).containsValue(0);
        assertThat(state.isMatchOver()).isFalse();
    }

    @Test
    void start_seat_is_normalised_into_range() {
        assertThat(SkullKingMatchState.initial(4, 6).startSeat()).isEqualTo(2);
        assertThat(SkullKingMatchState.initial(4, -1).startSeat()).isEqualTo(3);
    }

    /** §13-⑮ — 라운드마다 턴 순서 +1 로 시작 좌석이 옮겨간다. */
    @Test
    void start_seat_advances_by_one_each_round_and_wraps() {
        SkullKingMatchState state = SkullKingMatchState.initial(4, 3);

        state = state.withRoundScored(Map.of(0, 0, 1, 0, 2, 0, 3, 0), 4);
        assertThat(state.startSeat()).isZero();

        state = state.withRoundScored(Map.of(0, 0, 1, 0, 2, 0, 3, 0), 4);
        assertThat(state.startSeat()).isEqualTo(1);
    }

    @Test
    void round_scores_accumulate_including_negatives() {
        SkullKingMatchState state = SkullKingMatchState.initial(3, 0)
                .withRoundScored(Map.of(0, 20, 1, -10, 2, 0), 3)
                .withRoundScored(Map.of(0, -30, 1, 40, 2, 10), 3);

        assertThat(state.cumulativeScores()).containsEntry(0, -10)
                .containsEntry(1, 30)
                .containsEntry(2, 10);
        assertThat(state.roundNumber()).isEqualTo(3);
    }

    @Test
    void match_is_over_after_ten_rounds() {
        SkullKingMatchState state = SkullKingMatchState.initial(2, 0);
        for (int i = 0; i < SkullKingMatchState.TOTAL_ROUNDS; i++) {
            assertThat(state.isMatchOver()).as("round %d", state.roundNumber()).isFalse();
            state = state.withRoundScored(Map.of(0, 10, 1, 0), 2);
        }

        assertThat(state.roundNumber()).isEqualTo(11);
        assertThat(state.isMatchOver()).isTrue();
    }

    @Test
    void single_highest_score_is_the_sole_winner() {
        SkullKingMatchState state = SkullKingMatchState.initial(3, 0)
                .withRoundScored(Map.of(0, 40, 1, 20, 2, -10), 3);

        assertThat(state.winners()).containsExactly(0);
    }

    /** §13-⑰ — 원문에 타이브레이크 지표가 없어 공동 승리로 둔다. */
    @Test
    void tied_top_scores_share_the_win() {
        SkullKingMatchState state = SkullKingMatchState.initial(4, 0)
                .withRoundScored(Map.of(0, 40, 1, 20, 2, 40, 3, -10), 4);

        assertThat(state.winners()).containsExactly(0, 2);
    }

    @Test
    void all_negative_scores_still_produce_a_winner() {
        SkullKingMatchState state = SkullKingMatchState.initial(2, 0)
                .withRoundScored(Map.of(0, -30, 1, -10), 2);

        assertThat(state.winners()).containsExactly(1);
    }

    // ---------- 탈주 (D-104, §13-⑱⑲⑳) ----------

    @Test
    void the_three_arg_constructor_keeps_an_empty_deserted_set() {
        SkullKingMatchState state = new SkullKingMatchState(3, 1, Map.of(0, 10, 1, 20));

        assertThat(state.desertedSeats()).isEmpty();
    }

    @Test
    void a_null_deserted_set_normalises_to_empty_for_old_json() {
        SkullKingMatchState state = new SkullKingMatchState(1, 0, Map.of(0, 0), null);

        assertThat(state.desertedSeats()).isEmpty();
    }

    @Test
    void deserted_seats_accumulate_and_survive_round_boundaries() {
        SkullKingMatchState state = SkullKingMatchState.initial(4, 0)
                .withSeatDeserted(2)
                .withRoundScored(Map.of(0, 10, 1, 0, 2, -10, 3, 0), 4)
                .withSeatDeserted(0);

        assertThat(state.desertedSeats()).containsExactly(0, 2);
        assertThat(state.activeSeats()).containsExactly(1, 3);
    }

    @Test
    void winners_exclude_deserted_seats_even_at_the_top_of_the_board() {
        SkullKingMatchState state = SkullKingMatchState.initial(3, 0)
                .withRoundScored(Map.of(0, 90, 1, 20, 2, 40), 3)
                .withSeatDeserted(0);

        assertThat(state.winners()).containsExactly(2);
        assertThat(state.cumulativeScores())
                .as("점수 궤적은 계속 기록된다 (§13-⑳)")
                .containsEntry(0, 90);
    }

    @Test
    void surviving_ties_still_share_the_win() {
        SkullKingMatchState state = SkullKingMatchState.initial(4, 0)
                .withRoundScored(Map.of(0, 99, 1, 40, 2, 40, 3, 10), 4)
                .withSeatDeserted(0);

        assertThat(state.winners()).containsExactly(1, 2);
    }

    @Test
    void winners_is_empty_when_everyone_has_deserted() {
        SkullKingMatchState state = SkullKingMatchState.initial(2, 0)
                .withSeatDeserted(0)
                .withSeatDeserted(1);

        assertThat(state.winners()).isEmpty();
    }

    @Test
    void abandoned_jumps_past_the_last_round_and_keeps_scores() {
        SkullKingMatchState state = SkullKingMatchState.initial(2, 0)
                .withRoundScored(Map.of(0, 10, 1, 20), 2)
                .withSeatDeserted(0)
                .abandoned();

        assertThat(state.isMatchOver()).isTrue();
        assertThat(state.cumulativeScores()).containsEntry(0, 10).containsEntry(1, 20);
        assertThat(state.desertedSeats()).containsExactly(0);
    }
}
