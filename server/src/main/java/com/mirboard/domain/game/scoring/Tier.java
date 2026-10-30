package com.mirboard.domain.game.scoring;

/**
 * D-164 — 포트리스2식 21단계 계급. DB 컬럼이 아니라 조회 시 계산하는 파생값이다(D-40 과 같다). 낮은 것부터 선언한다.
 *
 * <p>포트리스는 상위 %로 정하지만 우리는 사람이 적어 들쭉날쭉하므로 <b>점수 구간</b>으로 정한다({@link #fromRating}). 시작 1000점이
 * 미사일 I 이고, 별까지 30점·메달 40점·훈장 50점마다 한 칸이다. 같은 수준을 이기면 약 +16(K=32)이라 두 판쯤마다 오르내린다.
 * 왕관 넷은 점수가 아니라 금훈장 이상인 사람의 게임별 순위로 정한다({@link #withRank}).
 */
public enum Tier {
    SKULL,
    BULLET_1,
    BULLET_2,
    BULLET_3,
    MISSILE_1,
    MISSILE_2,
    MISSILE_3,
    BRONZE_STAR,
    SILVER_STAR,
    GOLD_STAR,
    DOUBLE_GOLD_STAR,
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
    public static final int GOLD_BADGE_MIN = 1430;
    /** 왕관을 받는 마지막 순위. */
    public static final int LAST_CROWN_RANK = 30;

    /** 각 계급(해골 다음 ~ 금훈장)이 시작하는 점수. 순서는 enum 과 같다(해골은 그 아래 전부). */
    private static final int[] FLOORS = {
            910, 940, 970,               // 총알 I·II·III
            1000, 1030, 1060,            // 미사일 I·II·III (1000 = 시작)
            1090, 1120, 1150, 1180,      // 동별·은별·금별·쌍금별
            1210, 1250, 1290,            // 동·은·금메달
            1330, 1380, GOLD_BADGE_MIN,  // 동·은·금훈장
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
