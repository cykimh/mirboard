package com.mirboard.domain.game.skullking.card;

/**
 * 스컬킹 색상 카드의 4색 (`docs/rules-skullking.md` §1). 각 색 1~14 로 56장.
 *
 * <p>{@link #BLACK} 만 으뜸패다 — 리드 수트가 아니어도 다른 3색을 이긴다. 원문 각주가
 * "리드 수트와 다른 색은 반드시 패배"라고 무조건으로 쓰지만 본문의 으뜸패 정의가
 * 우선한다 (§7.1, §13-⑦).
 */
public enum SkullSuit {

    /** 초록 — 앵무새 (Parrot). */
    GREEN,

    /** 보라 — 지도 (Pirate Map). */
    PURPLE,

    /** 노랑 — 보물상자 (Treasure Chest). */
    YELLOW,

    /** 검정 — 해적기 (Jolly Roger). 유일한 으뜸패. */
    BLACK;

    /** 최저 숫자. 티츄와 달리 1 부터 시작한다. */
    public static final int MIN_RANK = 1;

    /** 최고 숫자. 14 는 보너스 대상이다 (§11). */
    public static final int MAX_RANK = 14;

    public boolean isTrump() {
        return this == BLACK;
    }
}
