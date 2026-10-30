package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-164·D-167 — 포트리스2식 16단계. 점수 구간으로 정하고, 왕관은 금훈장 이상의 게임별 순위로 정한다. */
class TierTest {

    @Test
    @DisplayName("시작 1000점은 미사일 — 아래로 총알(940)·해골")
    void starts_at_missile() {
        assertThat(Tier.fromRating(1000)).isEqualTo(Tier.MISSILE);
        assertThat(Tier.fromRating(1039)).isEqualTo(Tier.MISSILE);
        assertThat(Tier.fromRating(999)).isEqualTo(Tier.BULLET);
        assertThat(Tier.fromRating(940)).isEqualTo(Tier.BULLET);
        assertThat(Tier.fromRating(939)).isEqualTo(Tier.SKULL);
        assertThat(Tier.fromRating(0)).isEqualTo(Tier.SKULL);
    }

    @Test
    @DisplayName("별·메달·훈장 구간 — 금훈장(1400+)이 점수로 오르는 끝")
    void upper_bands() {
        assertThat(Tier.fromRating(1040)).isEqualTo(Tier.BRONZE_STAR);
        assertThat(Tier.fromRating(1080)).isEqualTo(Tier.SILVER_STAR);
        assertThat(Tier.fromRating(1159)).isEqualTo(Tier.GOLD_STAR);
        assertThat(Tier.fromRating(1160)).isEqualTo(Tier.BRONZE_MEDAL);
        assertThat(Tier.fromRating(1250)).isEqualTo(Tier.GOLD_MEDAL);
        assertThat(Tier.fromRating(1300)).isEqualTo(Tier.BRONZE_BADGE);
        assertThat(Tier.fromRating(1399)).isEqualTo(Tier.SILVER_BADGE);
        assertThat(Tier.fromRating(1400)).isEqualTo(Tier.GOLD_BADGE);
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
        assertThat(Tier.withRank(1130, 1)).isEqualTo(Tier.GOLD_STAR);
        assertThat(Tier.withRank(1000, 1)).isEqualTo(Tier.MISSILE);
    }

    @Test
    @DisplayName("16단계, 낮은 것부터 — 그림이 여러 개인 계급은 없다(D-167)")
    void sixteen_ranks_in_order() {
        assertThat(Tier.values()).hasSize(16);
        assertThat(Tier.values()[0]).isEqualTo(Tier.SKULL);
        assertThat(Tier.values()[2]).isEqualTo(Tier.MISSILE);
        assertThat(Tier.values()[15]).isEqualTo(Tier.STAR_CROWN);
    }
}
