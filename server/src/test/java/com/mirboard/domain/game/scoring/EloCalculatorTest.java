package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.scoring.EloCalculator.PlayerInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EloCalculatorTest {

    @Test
    void equal_ratings_with_default_K_yield_plus_minus_K_over_2() {
        // 4명 모두 rating 1000, gamesPlayed 50 (신규 아님 → K=32). expected=0.5 → delta=±16.
        var a1 = new PlayerInput(1, 1000, 50);
        var a2 = new PlayerInput(2, 1000, 50);
        var b1 = new PlayerInput(3, 1000, 50);
        var b2 = new PlayerInput(4, 1000, 50);
        Map<Long, Integer> out = EloCalculator.applyMatch(List.of(a1, a2), List.of(b1, b2), true);
        assertThat(out.get(1L)).isEqualTo(1016);
        assertThat(out.get(2L)).isEqualTo(1016);
        assertThat(out.get(3L)).isEqualTo(984);
        assertThat(out.get(4L)).isEqualTo(984);
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
        // delta = 32 * (0 - 0.909...) ≈ -29.
        assertThat(out.get(1L)).isEqualTo(1371);
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

    @Test
    void tier_boundaries() {
        assertThat(Tier.fromRating(0)).isEqualTo(Tier.BRONZE);
        assertThat(Tier.fromRating(1099)).isEqualTo(Tier.BRONZE);
        assertThat(Tier.fromRating(1100)).isEqualTo(Tier.SILVER);
        assertThat(Tier.fromRating(1249)).isEqualTo(Tier.SILVER);
        assertThat(Tier.fromRating(1250)).isEqualTo(Tier.GOLD);
        assertThat(Tier.fromRating(1400)).isEqualTo(Tier.PLATINUM);
        assertThat(Tier.fromRating(1550)).isEqualTo(Tier.DIAMOND);
        assertThat(Tier.fromRating(1700)).isEqualTo(Tier.MASTER);
        assertThat(Tier.fromRating(9999)).isEqualTo(Tier.MASTER);
    }
}
