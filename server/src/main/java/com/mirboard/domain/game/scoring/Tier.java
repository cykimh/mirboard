package com.mirboard.domain.game.scoring;

/**
 * Phase 8D — ELO 점수 구간에서 파생되는 6단계 티어 (D-40). DB 컬럼이 아니라
 * 조회 시 {@link #fromRating(int)} 로 계산한다. 구간:
 *
 * <ul>
 *   <li>BRONZE   &lt; 1100</li>
 *   <li>SILVER   1100–1249</li>
 *   <li>GOLD     1250–1399</li>
 *   <li>PLATINUM 1400–1549</li>
 *   <li>DIAMOND  1550–1699</li>
 *   <li>MASTER   ≥ 1700</li>
 * </ul>
 */
public enum Tier {
    BRONZE,
    SILVER,
    GOLD,
    PLATINUM,
    DIAMOND,
    MASTER;

    public static Tier fromRating(int rating) {
        if (rating >= 1700) return MASTER;
        if (rating >= 1550) return DIAMOND;
        if (rating >= 1400) return PLATINUM;
        if (rating >= 1250) return GOLD;
        if (rating >= 1100) return SILVER;
        return BRONZE;
    }
}
