package com.mirboard.domain.game.skullking.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 라운드 점수 (§10, §13-⑫⑬). */
class RoundScorerTest {

    /** 카드 내용이 무관한 경우의 더미 트릭 — 보너스 0 이 되도록 색상 저숫자만 담는다. */
    private static TrickResult plainTrick(int winnerSeat) {
        PlayedCard winner = PlayedCard.of(winnerSeat, SkullCard.of(SkullSuit.GREEN, 2));
        return new TrickResult(winnerSeat, winner, List.of(winner));
    }

    private static PlayerState playerWith(int bid, int tricksWon) {
        List<TrickResult> tricks = new ArrayList<>();
        for (int i = 0; i < tricksWon; i++) {
            tricks.add(plainTrick(0));
        }
        return new PlayerState(0, List.of(), bid, tricks);
    }

    @Nested
    @DisplayName("§10 표 4경우")
    class SpecTable {

        @ParameterizedTest(name = "bid={0} won={1} R={2} → {3}")
        @CsvSource({
                // 적중 (bid > 0) — 승수 × 20
                "1, 1, 1,   20",
                "3, 3, 5,   60",
                "10, 10, 10, 200",
                // 실패 (bid > 0) — 차이 × 10 감점
                "3, 1, 5,  -20",
                "1, 3, 5,  -20",
                "5, 0, 7,  -50",
                // 0 예측 적중 — 라운드 번호 × 10
                "0, 0, 1,   10",
                "0, 0, 7,   70",
                "0, 0, 10, 100",
                // 0 예측 실패 — 정액 라운드 번호 × 10 감점
                "0, 1, 1,  -10",
                "0, 2, 7,  -70",
                "0, 1, 10, -100"
        })
        void base_score_matches(int bid, int won, int round, int expected) {
            assertThat(RoundScorer.baseScore(bid, won, round)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("§13-⑬ 판별 테스트 — 0 예측 실패는 정액이다")
    class ZeroBidFailure {

        /**
         * 명세 §10 이 지정한 판별 케이스. 일반칙(차이 × 10) 해석이면 −10, 0 예측
         * 특칙(라운드 × 10) 해석이면 −30 이다. 두 해석을 구분하는 유일한 자리.
         */
        @Test
        void round_three_bid_zero_won_one_is_minus_thirty_not_minus_ten() {
            assertThat(RoundScorer.baseScore(0, 1, 3))
                    .as("특칙이 일반칙을 대체한다 — 차이(1)×10 이 아니라 라운드(3)×10")
                    .isEqualTo(-30);
        }

        /**
         * 명세가 경고한 함정 — 이 케이스는 두 해석이 우연히 같아져 회귀를 못 잡는다.
         * 위 판별 테스트가 있어야 하는 이유를 코드에 남긴다.
         */
        @Test
        void round_ten_bid_zero_won_ten_coincides_under_both_readings() {
            assertThat(RoundScorer.baseScore(0, 10, 10)).isEqualTo(-100);
        }

        @Test
        void the_penalty_does_not_grow_with_the_number_of_tricks_taken() {
            assertThat(RoundScorer.baseScore(0, 1, 5)).isEqualTo(-50);
            assertThat(RoundScorer.baseScore(0, 3, 5)).isEqualTo(-50);
            assertThat(RoundScorer.baseScore(0, 5, 5))
                    .as("정액이므로 몇 트릭을 땄든 감점이 같다")
                    .isEqualTo(-50);
        }
    }

    @Nested
    @DisplayName("§13-⑫ 0 예측 점수는 라운드 번호를 쓴다")
    class RoundNumberNotTrickCount {

        /**
         * 8인 라운드 9·10 은 손패가 둘 다 8장이지만 0 예측 성공 점수는 90 / 100 으로
         * 갈린다 — 트릭 수가 아니라 라운드 번호를 쓰기 때문이다.
         */
        @Test
        void eight_player_rounds_nine_and_ten_differ_despite_equal_hand_sizes() {
            assertThat(RoundScorer.baseScore(0, 0, 9)).isEqualTo(90);
            assertThat(RoundScorer.baseScore(0, 0, 10)).isEqualTo(100);
        }

        @Test
        void the_same_asymmetry_applies_to_failure() {
            assertThat(RoundScorer.baseScore(0, 1, 9)).isEqualTo(-90);
            assertThat(RoundScorer.baseScore(0, 1, 10)).isEqualTo(-100);
        }
    }

    @Nested
    @DisplayName("보너스 게이트 (§11)")
    class BonusGate {

        @Test
        void a_hit_bid_keeps_its_bonus() {
            PlayedCard blackFourteen = PlayedCard.of(0, SkullCard.of(SkullSuit.BLACK, 14));
            TrickResult trick = new TrickResult(0, blackFourteen, List.of(blackFourteen));
            PlayerState player = new PlayerState(0, List.of(), 1, List.of(trick));

            RoundScore score = RoundScorer.score(player, 4);

            assertThat(score.bidHit()).isTrue();
            assertThat(score.base()).isEqualTo(20);
            assertThat(score.bonus()).isEqualTo(BonusCalculator.BLACK_FOURTEEN);
            assertThat(score.total()).isEqualTo(40);
        }

        @Test
        void a_missed_bid_loses_the_entire_bonus() {
            PlayedCard blackFourteen = PlayedCard.of(0, SkullCard.of(SkullSuit.BLACK, 14));
            TrickResult trick = new TrickResult(0, blackFourteen, List.of(blackFourteen));
            PlayerState player = new PlayerState(0, List.of(), 2, List.of(trick));

            RoundScore score = RoundScorer.score(player, 4);

            assertThat(score.bidHit()).isFalse();
            assertThat(score.bonus())
                    .as("적중 실패면 딴 카드와 무관하게 보너스 0")
                    .isZero();
            assertThat(score.total()).isEqualTo(-10);
        }

        @Test
        void a_zero_bid_hit_also_earns_bonuses_but_has_no_tricks_to_earn_them_from() {
            PlayerState player = playerWith(0, 0);

            RoundScore score = RoundScorer.score(player, 6);

            assertThat(score.bidHit()).isTrue();
            assertThat(score.bonus()).isZero();
            assertThat(score.total()).isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("좌석별 집계")
    class ScoreAll {

        @Test
        void scores_every_seat_and_preserves_seat_keys() {
            List<PlayerState> players = List.of(
                    new PlayerState(0, List.of(), 1, List.of(plainTrick(0))),
                    new PlayerState(1, List.of(), 0, List.of()),
                    new PlayerState(2, List.of(), 2, List.of(plainTrick(2))));

            Map<Integer, RoundScore> scores = RoundScorer.scoreAll(players, 3);

            assertThat(scores).containsOnlyKeys(0, 1, 2);
            assertThat(scores.get(0).total()).isEqualTo(20);
            assertThat(scores.get(1).total()).isEqualTo(30);
            assertThat(scores.get(2).total()).isEqualTo(-10);
        }
    }

    @Nested
    @DisplayName("RoundScore 자체 불변식")
    class ScoreRecord {

        @Test
        void a_bonus_on_a_missed_bid_is_a_programming_error() {
            assertThat(new RoundScore(1, 1, 20, 30).total()).isEqualTo(50);
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new RoundScore(2, 1, -10, 30))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("§11");
        }

        @Test
        void bid_hit_reflects_bid_equals_won() {
            assertThat(new RoundScore(0, 0, 10, 0).bidHit()).isTrue();
            assertThat(new RoundScore(3, 3, 60, 0).bidHit()).isTrue();
            assertThat(new RoundScore(3, 2, -10, 0).bidHit()).isFalse();
        }
    }
}
