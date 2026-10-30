package com.mirboard.domain.game.scoring;

/**
 * D-164·D-167 — 포트리스2식 16단계 계급. DB 컬럼이 아니라 조회 시 계산하는 파생값이다(D-40 과 같다). 낮은 것부터 선언한다.
 *
 * <p>포트리스는 상위 %로 정하지만 우리는 사람이 적어 들쭉날쭉하므로 <b>점수 구간</b>으로 정한다({@link #fromRating}). 시작 1000점이
 * 미사일이고, 40~50점마다 한 칸이다. 같은 수준을 이기면 약 +16(K=32)이라 두세 판마다 오르내린다. 그림이 여러 개인 계급(총알 II·III,
 * 미사일 II·III, 쌍금별)은 D-167 에서 뺐다. 왕관 넷은 점수가 아니라 금훈장 이상인 사람의 게임별 순위로 정한다({@link #withRank}).
 */
public enum Tier {
    SKULL,
    BULLET,
    MISSILE,
    BRONZE_STAR,
    SILVER_STAR,
    GOLD_STAR,
    BRONZE_MEDAL,
    SILVER_MEDAL,
    GOLD_MEDAL,
    BRONZE_BADGE,
    SILVER_BADGE,
    GOLD_BADGE,
    BRONZE_CROWN,
    SILVER_CROWN,
    GOLD_CROWN,
    STAR_CROWN;

    /** 점수로 오르는 끝 — 이 계급부터 순위로 왕관을 받는다. */
    public static final int GOLD_BADGE_MIN = 1400;
    /** 왕관을 받는 마지막 순위. */
    public static final int LAST_CROWN_RANK = 30;

    /** 각 계급(해골 다음 ~ 금훈장)이 시작하는 점수. 순서는 enum 과 같다(해골은 그 아래 전부). */
    private static final int[] FLOORS = {
            940,                         // 총알
            1000,                        // 미사일 (= 시작)
            1040, 1080, 1120,            // 동별·은별·금별
            1160, 1200, 1250,            // 동·은·금메달
            1300, 1350, GOLD_BADGE_MIN,  // 동·은·금훈장
    };

    /** 점수 구간 계급(해골 ~ 금훈장). 왕관은 순위가 있어야 정해진다 — {@link #withRank}. */
    public static Tier fromRating(int rating) {
        Tier tier = SKULL;
        for (int i = 0; i < FLOORS.length; i++) {
            if (rating >= FLOORS[i]) {
                tier = values()[i + 1];
            }
        }
        return tier;
    }

    /**
     * 순위까지 본 계급. 금훈장 이상이면 게임별 순위로 왕관이다: 1위 별왕관, 2~3위 금왕관, 4~10위 은왕관, 11~30위 동왕관.
     *
     * @param rank 그 게임 랭킹 순위(1부터, 봇·게스트 제외)
     */
    public static Tier withRank(int rating, int rank) {
        Tier base = fromRating(rating);
        if (base != GOLD_BADGE || rank < 1) {
            return base;
        }
        if (rank == 1) return STAR_CROWN;
        if (rank <= 3) return GOLD_CROWN;
        if (rank <= 10) return SILVER_CROWN;
        if (rank <= LAST_CROWN_RANK) return BRONZE_CROWN;
        return GOLD_BADGE;
    }
}
