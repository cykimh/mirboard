package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-164 — 포트리스2식 21단계. 점수 구간으로 정하고, 왕관은 금훈장 이상의 게임별 순위로 정한다. */
class TierTest {

    @Test
    @DisplayName("시작 1000점은 미사일 I — 30점마다 한 칸(별까지)")
    void starts_at_missile_one_and_moves_every_30_points() {
        assertThat(Tier.fromRating(1000)).isEqualTo(Tier.MISSILE_1);
        assertThat(Tier.fromRating(1029)).isEqualTo(Tier.MISSILE_1);
        assertThat(Tier.fromRating(1030)).isEqualTo(Tier.MISSILE_2);
        assertThat(Tier.fromRating(999)).isEqualTo(Tier.BULLET_3);
        assertThat(Tier.fromRating(970)).isEqualTo(Tier.BULLET_3);
        assertThat(Tier.fromRating(969)).isEqualTo(Tier.BULLET_2);
        assertThat(Tier.fromRating(910)).isEqualTo(Tier.BULLET_1);
        assertThat(Tier.fromRating(909)).isEqualTo(Tier.SKULL);
        assertThat(Tier.fromRating(0)).isEqualTo(Tier.SKULL);
    }

    @Test
    @DisplayName("별·메달·훈장 구간 — 금훈장(1430+)이 점수로 오르는 끝")
    void upper_bands() {
        assertThat(Tier.fromRating(1090)).isEqualTo(Tier.BRONZE_STAR);
        assertThat(Tier.fromRating(1180)).isEqualTo(Tier.DOUBLE_GOLD_STAR);
        assertThat(Tier.fromRating(1209)).isEqualTo(Tier.DOUBLE_GOLD_STAR);
        assertThat(Tier.fromRating(1210)).isEqualTo(Tier.BRONZE_MEDAL);
        assertThat(Tier.fromRating(1290)).isEqualTo(Tier.GOLD_MEDAL);
        assertThat(Tier.fromRating(1330)).isEqualTo(Tier.BRONZE_BADGE);
        assertThat(Tier.fromRating(1429)).isEqualTo(Tier.SILVER_BADGE);
        assertThat(Tier.fromRating(1430)).isEqualTo(Tier.GOLD_BADGE);
        assertThat(Tier.fromRating(9999)).isEqualTo(Tier.GOLD_BADGE);
    }

    @Test
    @DisplayName("금훈장이면 순위로 왕관 — 1위 별왕관, 2~3 금, 4~10 은, 11~30 동, 그 아래는 금훈장")
    void crowns_by_rank_for_gold_badges() {
        assertThat(Tier.withRank(1500, 1)).isEqualTo(Tier.STAR_CROWN);
        assertThat(Tier.withRank(1500, 3)).isEqualTo(Tier.GOLD_CROWN);
        assertThat(Tier.withRank(1500, 4)).isEqualTo(Tier.SILVER_CROWN);
        assertThat(Tier.withRank(1500, 10)).isEqualTo(Tier.SILVER_CROWN);
        assertThat(Tier.withRank(1500, 30)).isEqualTo(Tier.BRONZE_CROWN);
        assertThat(Tier.withRank(1500, 31)).isEqualTo(Tier.GOLD_BADGE);
    }

    @Test
    @DisplayName("금훈장 아래는 1위여도 왕관이 아니다")
    void no_crown_below_gold_badge() {
        assertThat(Tier.withRank(1160, 1)).isEqualTo(Tier.GOLD_STAR);
        assertThat(Tier.withRank(1000, 1)).isEqualTo(Tier.MISSILE_1);
    }

    @Test
    @DisplayName("21단계, 낮은 것부터")
    void twenty_one_ranks_in_order() {
        assertThat(Tier.values()).hasSize(21);
        assertThat(Tier.values()[0]).isEqualTo(Tier.SKULL);
        assertThat(Tier.values()[20]).isEqualTo(Tier.STAR_CROWN);
    }
}
