package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.scoring.EloCalculator.Placement;
import com.mirboard.domain.game.scoring.EloCalculator.PlayerInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EloCalculatorTest {

    @Test
    void equal_ratings_with_default_K_yield_plus_minus_K_over_2() {
        // 4명 모두 rating 1000, gamesPlayed 50 (신규 아님 → K=32). expected=0.5 → delta=+16 / −16×½(D-297) = −8.
        var a1 = new PlayerInput(1, 1000, 50);
        var a2 = new PlayerInput(2, 1000, 50);
        var b1 = new PlayerInput(3, 1000, 50);
        var b2 = new PlayerInput(4, 1000, 50);
        Map<Long, Integer> out = EloCalculator.applyMatch(List.of(a1, a2), List.of(b1, b2), true);
        assertThat(out.get(1L)).isEqualTo(1016);
        assertThat(out.get(2L)).isEqualTo(1016);
        assertThat(out.get(3L)).isEqualTo(992);
        assertThat(out.get(4L)).isEqualTo(992);
    }

    @Test
    void same_team_players_get_equal_delta_with_mixed_K() {
        // teamA: 신규 + 베테랑. 같은 팀이라도 K 가 다르면 delta 도 달라야 함.
        var rookie = new PlayerInput(1, 1000, 5);   // K=40
        var vet = new PlayerInput(2, 1000, 100);     // K=32
        var b1 = new PlayerInput(3, 1000, 100);
        var b2 = new PlayerInput(4, 1000, 100);
        Map<Long, Integer> out = EloCalculator.applyMatch(List.of(rookie, vet), List.of(b1, b2), true);
        // expected=0.5 (avg equal). delta = K * 0.5.
        assertThat(out.get(1L)).isEqualTo(1020);  // 40 * 0.5
        assertThat(out.get(2L)).isEqualTo(1016);  // 32 * 0.5
    }

    @Test
    void higher_rated_team_loses_more_when_upset() {
        // teamA avg=1400, teamB avg=1000 → expectedA ≈ 0.91. 만약 B 가 승리하면 A 는 큰 점수 잃음.
        var a1 = new PlayerInput(1, 1400, 50);
        var a2 = new PlayerInput(2, 1400, 50);
        var b1 = new PlayerInput(3, 1000, 50);
        var b2 = new PlayerInput(4, 1000, 50);
        Map<Long, Integer> out = EloCalculator.applyMatch(List.of(a1, a2), List.of(b1, b2), false);
        // delta = 32 * (0 - 0.909...) ≈ -29 → 깎일 때 절반(D-297) ≈ -15.
        assertThat(out.get(1L)).isEqualTo(1385);
        assertThat(out.get(3L)).isEqualTo(1029);
    }

    @Test
    void k_factor_uses_new_player_value_below_threshold() {
        assertThat(EloCalculator.kFactor(0)).isEqualTo(40);
        assertThat(EloCalculator.kFactor(29)).isEqualTo(40);
        assertThat(EloCalculator.kFactor(30)).isEqualTo(32);
        assertThat(EloCalculator.kFactor(100)).isEqualTo(32);
    }

    @Test
    void empty_team_is_rejected() {
        assertThatThrownBy(() -> EloCalculator.applyMatch(List.of(),
                List.of(new PlayerInput(1, 1000, 50)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 계급 구간은 TierTest(D-164).

    // ---------- D-115 — 개인전(free-for-all) 쌍대 ELO ----------

    private static Placement placed(long userId, int rating, int games, int score) {
        return new Placement(new PlayerInput(userId, rating, games), score, false);
    }

    @Test
    void ffa_two_players_matches_plain_elo() {
        // 2인이면 쌍이 하나뿐이라 K/(n-1)=K — 일반 ELO 와 같아야 한다.
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1000, 50, 80), placed(2, 1000, 50, 20)));
        assertThat(out).containsEntry(1L, 1016).containsEntry(2L, 992);
    }

    @Test
    void ffa_four_players_equal_ratings_spread_by_placement() {
        // 각자 3쌍. 1등은 3승 → 32/3 × (3 − 1.5) = 16, 2등은 2승 1패 → 32/3 × 0.5 ≈ 5.
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1000, 50, 120), placed(2, 1000, 50, 60),
                placed(3, 1000, 50, 10), placed(4, 1000, 50, -40)));
        assertThat(out).containsEntry(1L, 1016).containsEntry(2L, 1005)
                .containsEntry(3L, 997).containsEntry(4L, 992);
    }

    @Test
    void ffa_tied_scores_count_as_draw() {
        // 공동 승리(§13-⑰) — 같은 점수는 0.5. 레이팅이 같으면 둘 다 변동 없음.
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1000, 50, 70), placed(2, 1000, 50, 70)));
        assertThat(out).containsEntry(1L, 1000).containsEntry(2L, 1000);
    }

    @Test
    void ffa_deserter_ranks_last_regardless_of_score() {
        // 탈주 직전 점수가 가장 높아도 레이팅을 얻으면 안 된다.
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                new Placement(new PlayerInput(1, 1000, 50), 300, true),
                placed(2, 1000, 50, 10)));
        assertThat(out).containsEntry(1L, 992).containsEntry(2L, 1016);
    }

    @Test
    void ffa_needs_at_least_two_players() {
        assertThatThrownBy(() -> EloCalculator.applyFreeForAll(List.of(placed(1, 1000, 50, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void d297_losses_are_halved_gains_are_not() {
        // 2인 K=40, 레이팅 같음 → 이긴 쪽 +20, 진 쪽 −20×½ = −10. 증감 합은 +10(인플레이션을 받아들인다).
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1000, 0, 50), placed(2, 1000, 0, 10)));
        assertThat(out).containsEntry(1L, 1020).containsEntry(2L, 990);
        assertThat(EloCalculator.LOSS_FACTOR).isEqualTo(0.5);
    }

    // ---------- D-299 — 봇이 낀 판의 배율 ----------

    @Test
    void d299_bot_match_gain_is_a_quarter() {
        // 2인 K=40, 봇은 1000·0판 → 사람끼리면 +20, 봇 판이면 +20×¼ = +5.
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1000, 0, 50), placed(2, 1000, 0, 10)), EloCalculator.BOT_MATCH_FACTOR);
        assertThat(out).containsEntry(1L, 1005);
        assertThat(EloCalculator.BOT_MATCH_FACTOR).isEqualTo(0.25);
    }

    @Test
    void d299_bot_match_loss_is_a_quarter_of_the_halved_loss() {
        // K=32: 사람끼리 −16×½ = −8, 봇 판이면 −8×¼ = −2. 반올림은 다 곱한 뒤 한 번.
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1000, 50, 10), placed(2, 1000, 50, 50)), EloCalculator.BOT_MATCH_FACTOR);
        assertThat(out).containsEntry(1L, 998).containsEntry(2L, 1004);
        // 한 번만 반올림 — −7.4 를 먼저 −7 로 만들고 ¼ 을 곱하지 않는다.
        assertThat(EloCalculator.delta(-14.8, 0.25)).isEqualTo(-2);
        assertThat(EloCalculator.delta(14.8, 0.25)).isEqualTo(4);
    }

    @Test
    void d299_team_match_scales_both_teams() {
        // 팀 평균 1000 vs 1000, K=32 → 사람끼리 +16/−8, 봇 판 +4/−2.
        Map<Long, Integer> out = EloCalculator.applyMatch(
                List.of(new PlayerInput(1, 1000, 50), new PlayerInput(2, 1000, 50)),
                List.of(new PlayerInput(3, 1000, 50), new PlayerInput(4, 1000, 50)),
                true, EloCalculator.BOT_MATCH_FACTOR);
        assertThat(out).containsEntry(1L, 1004).containsEntry(2L, 1004)
                .containsEntry(3L, 998).containsEntry(4L, 998);
    }

    @Test
    void d299_full_factor_equals_the_plain_call() {
        List<Placement> placements = List.of(placed(1, 1100, 10, 50), placed(2, 950, 80, 10), placed(3, 1000, 5, 30));
        assertThat(EloCalculator.applyFreeForAll(placements, EloCalculator.FULL_FACTOR))
                .isEqualTo(EloCalculator.applyFreeForAll(placements));
    }

    @Test
    void d299_factor_out_of_range_is_rejected() {
        List<Placement> placements = List.of(placed(1, 1000, 0, 50), placed(2, 1000, 0, 10));
        assertThatThrownBy(() -> EloCalculator.applyFreeForAll(placements, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EloCalculator.applyFreeForAll(placements, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void d299_beating_a_fixed_1000_bot_stops_paying_near_1470() {
        // 봇은 늘 1000 — 사람이 오를수록 이겨 얻는 몫이 줄어 반올림 0 이 된다(쉬운 봇 반복의 천장).
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1480, 100, 50), placed(2, 1000, 0, 10)), EloCalculator.BOT_MATCH_FACTOR);
        assertThat(out).containsEntry(1L, 1480);
    }

    // ---------- D-301 — 참가 점수 ----------

    @Test
    void d301_humans_only_match_pays_six_whatever_the_result() {
        assertThat(EloCalculator.participationBonus(EloCalculator.FULL_FACTOR, false)).isEqualTo(6);
        assertThat(EloCalculator.PARTICIPATION_BONUS).isEqualTo(6);
    }

    @Test
    void d301_bot_match_pays_two() {
        assertThat(EloCalculator.participationBonus(EloCalculator.BOT_MATCH_FACTOR, false)).isEqualTo(2);
        assertThat(EloCalculator.BOT_MATCH_PARTICIPATION_BONUS).isEqualTo(2);
    }

    @Test
    void d301_deserters_and_unrated_matches_get_nothing() {
        assertThat(EloCalculator.participationBonus(EloCalculator.FULL_FACTOR, true)).isZero();
        assertThat(EloCalculator.participationBonus(EloCalculator.BOT_MATCH_FACTOR, true)).isZero();
        // 배율 0(ELO 를 적용하지 않는 기록)은 참가 점수도 없다.
        assertThat(EloCalculator.participationBonus(RatedMatchPolicy.UNRATED, false)).isZero();
    }

    @Test
    void d301_the_bonus_is_not_inside_the_elo_result() {
        // 계산 결과는 그대로 +20/−10 — 참가 점수는 저장할 때 UserGameStatsService.record 가 더한다.
        Map<Long, Integer> out = EloCalculator.applyFreeForAll(List.of(
                placed(1, 1000, 0, 50), placed(2, 1000, 0, 10)), EloCalculator.FULL_FACTOR);
        assertThat(out).containsEntry(1L, 1020).containsEntry(2L, 990);
    }
}
