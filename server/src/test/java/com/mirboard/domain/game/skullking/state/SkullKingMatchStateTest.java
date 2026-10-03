package com.mirboard.domain.game.skullking.state;

import static com.mirboard.domain.game.skullking.state.MatchStateFixtures.scored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.skullking.scoring.RoundScore;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        state = scored(state, Map.of(0, 0, 1, 0, 2, 0, 3, 0), 4);
        assertThat(state.startSeat()).isZero();

        state = scored(state, Map.of(0, 0, 1, 0, 2, 0, 3, 0), 4);
        assertThat(state.startSeat()).isEqualTo(1);
    }

    @Test
    void round_scores_accumulate_including_negatives() {
        SkullKingMatchState state = scored(
                scored(SkullKingMatchState.initial(3, 0), Map.of(0, 20, 1, -10, 2, 0), 3),
                Map.of(0, -30, 1, 40, 2, 10), 3);

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
            state = scored(state, Map.of(0, 10, 1, 0), 2);
        }

        assertThat(state.roundNumber()).isEqualTo(11);
        assertThat(state.isMatchOver()).isTrue();
    }

    @Test
    void single_highest_score_is_the_sole_winner() {
        SkullKingMatchState state =
                scored(SkullKingMatchState.initial(3, 0), Map.of(0, 40, 1, 20, 2, -10), 3);

        assertThat(state.winners()).containsExactly(0);
    }

    /** §13-⑰ — 원문에 타이브레이크 지표가 없어 공동 승리로 둔다. */
    @Test
    void tied_top_scores_share_the_win() {
        SkullKingMatchState state =
                scored(SkullKingMatchState.initial(4, 0), Map.of(0, 40, 1, 20, 2, 40, 3, -10), 4);

        assertThat(state.winners()).containsExactly(0, 2);
    }

    @Test
    void all_negative_scores_still_produce_a_winner() {
        SkullKingMatchState state =
                scored(SkullKingMatchState.initial(2, 0), Map.of(0, -30, 1, -10), 2);

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
        SkullKingMatchState state = scored(SkullKingMatchState.initial(4, 0).withSeatDeserted(2),
                Map.of(0, 10, 1, 0, 2, -10, 3, 0), 4)
                .withSeatDeserted(0);

        assertThat(state.desertedSeats()).containsExactly(0, 2);
        assertThat(state.activeSeats()).containsExactly(1, 3);
    }

    @Test
    void winners_exclude_deserted_seats_even_at_the_top_of_the_board() {
        SkullKingMatchState state =
                scored(SkullKingMatchState.initial(3, 0), Map.of(0, 90, 1, 20, 2, 40), 3)
                        .withSeatDeserted(0);

        assertThat(state.winners()).containsExactly(2);
        assertThat(state.cumulativeScores())
                .as("점수 궤적은 계속 기록된다 (§13-⑳)")
                .containsEntry(0, 90);
    }

    @Test
    void surviving_ties_still_share_the_win() {
        SkullKingMatchState state =
                scored(SkullKingMatchState.initial(4, 0), Map.of(0, 99, 1, 40, 2, 40, 3, 10), 4)
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
        SkullKingMatchState state =
                scored(SkullKingMatchState.initial(2, 0), Map.of(0, 10, 1, 20), 2)
                        .withSeatDeserted(0)
                        .abandoned();

        assertThat(state.isMatchOver()).isTrue();
        assertThat(state.cumulativeScores()).containsEntry(0, 10).containsEntry(1, 20);
        assertThat(state.desertedSeats()).containsExactly(0);
    }

    // ---------- 라운드 기록 (D-120) ----------

    private static final Map<Integer, RoundScore> ROUND_ONE = Map.of(
            0, new RoundScore(1, 1, 20, 10),
            1, new RoundScore(0, 1, -10, 0),
            2, new RoundScore(0, 0, 10, 0));

    @Test
    void withRoundCompleted_appends_history_accumulates_and_rotates() {
        SkullKingMatchState state = SkullKingMatchState.initial(3, 2)
                .withRoundCompleted(1, ROUND_ONE, 3);

        assertThat(state.completedRounds())
                .containsExactly(new SkullKingMatchState.CompletedRound(1, ROUND_ONE));
        assertThat(state.cumulativeScores())
                .as("누적은 라운드 total(기본+보너스)의 합")
                .containsEntry(0, 30).containsEntry(1, -10).containsEntry(2, 10);
        assertThat(state.roundNumber()).isEqualTo(2);
        assertThat(state.startSeat()).isZero();
    }

    @Test
    void history_keeps_rounds_in_order_and_survives_many_rounds() {
        SkullKingMatchState state = SkullKingMatchState.initial(2, 0);
        for (int i = 0; i < SkullKingMatchState.TOTAL_ROUNDS; i++) {
            state = scored(state, Map.of(0, 10, 1, -10), 2);
        }

        assertThat(state.completedRounds())
                .extracting(SkullKingMatchState.CompletedRound::roundNumber)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(state.cumulativeScores()).containsEntry(0, 100).containsEntry(1, -100);
    }

    /**
     * 정상 경로의 fail-fast 가드 — 같은 라운드를 두 번 정산하거나 라운드를 건너뛰면
     * 기록과 누적이 조용히 어긋난다. 복구는 어댑터의 {@code hasSettled} 사전 분기 몫이다.
     */
    @Test
    void withRoundCompleted_rejects_round_number_mismatch() {
        SkullKingMatchState state = new SkullKingMatchState(3, 0, Map.of(0, 0, 1, 0, 2, 0));

        assertThatThrownBy(() -> state.withRoundCompleted(2, ROUND_ONE, 3))
                .as("이미 정산한 과거 라운드")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.withRoundCompleted(4, ROUND_ONE, 3))
                .as("아직 오지 않은 미래 라운드")
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.hasSettled(2)).isTrue();
        assertThat(state.hasSettled(3)).isFalse();
        assertThat(state.hasSettled(4)).isFalse();
    }

    @Test
    void desertion_and_abandon_keep_history() {
        SkullKingMatchState played = scored(
                scored(SkullKingMatchState.initial(3, 0), Map.of(0, 10, 1, 0, 2, 20), 3),
                Map.of(0, -10, 1, 30, 2, 0), 3);
        List<SkullKingMatchState.CompletedRound> history = played.completedRounds();

        SkullKingMatchState deserted = played.withSeatDeserted(1);
        SkullKingMatchState abandoned = deserted.abandoned();

        assertThat(history).hasSize(2);
        assertThat(deserted.completedRounds()).isEqualTo(history);
        assertThat(abandoned.completedRounds())
                .as("조기 종료는 진행 중 라운드를 기록하지 않고 앞선 기록은 그대로 둔다 (§13-⑲)")
                .isEqualTo(history);
    }

    // ---------- D-122 — 완주 라운드 수는 매치가 끝날 때 확정해 저장한다 ----------

    @Test
    void rounds_played_is_unset_while_the_match_is_running() {
        SkullKingMatchState state = scored(SkullKingMatchState.initial(3, 0),
                Map.of(0, 10, 1, 0, 2, 20), 3);

        assertThat(state.roundsPlayed()).isNull();
        assertThat(state.withSeatDeserted(1).roundsPlayed()).isNull();
    }

    @Test
    void finishing_the_tenth_round_records_ten_rounds_played() {
        SkullKingMatchState state = SkullKingMatchState.initial(2, 0);
        for (int i = 0; i < SkullKingMatchState.TOTAL_ROUNDS; i++) {
            state = scored(state, Map.of(0, 10, 1, -10), 2);
        }

        assertThat(state.isMatchOver()).isTrue();
        assertThat(state.roundsPlayed()).isEqualTo(SkullKingMatchState.TOTAL_ROUNDS);
    }

    /** 조기 종료 — 진행 중이던 라운드는 폐기되므로 그 앞까지가 완주다 (§13-⑲). */
    @Test
    void abandoning_records_the_rounds_completed_before_the_jump() {
        SkullKingMatchState inRoundThree = scored(
                scored(SkullKingMatchState.initial(3, 0), Map.of(0, 10, 1, 0, 2, 20), 3),
                Map.of(0, -10, 1, 30, 2, 0), 3);

        assertThat(SkullKingMatchState.initial(3, 0).abandoned().roundsPlayed())
                .as("1라운드 도중 종료 = 완주 0")
                .isZero();
        assertThat(inRoundThree.abandoned().roundsPlayed()).isEqualTo(2);
        assertThat(inRoundThree.abandoned().abandoned().roundsPlayed())
                .as("이미 끝난 매치를 다시 버려도 값이 흔들리지 않는다")
                .isEqualTo(2);
    }

    @Test
    void null_history_normalises_to_empty_for_old_json() {
        SkullKingMatchState state = new SkullKingMatchState(1, 0, Map.of(0, 0), null, null);

        assertThat(state.completedRounds()).isEmpty();
        assertThat(new SkullKingMatchState(2, 0, Map.of(0, 0), Set.of(0))
                .completedRounds())
                .as("4-인자 보조 생성자도 빈 기록")
                .isEmpty();
    }
}
