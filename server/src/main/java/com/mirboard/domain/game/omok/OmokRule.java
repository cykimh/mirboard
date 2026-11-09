package com.mirboard.domain.game.omok;

/**
 * 오목 룰 3종 (룰 §5·§6.1, D-177). 방 설정 {@code OMOK_RULE} 의 값이다.
 *
 * <p>정의는 하나(렌주)이고 룰은 <b>흑의 승리 줄 길이와 켜는 금수 집합</b>만 다르다. 백에게는 어느 룰에서도 금수가 없고
 * 5 이상이면 이긴다.
 */
public enum OmokRule {
    /** 렌주(기본) — 흑만 33·44·장목 금지, 흑은 정확히 5. */
    RENJU(true, true, true, true),
    /** 삼삼만 금지 — 흑 33 만 금지, 흑도 5 이상. */
    DOUBLE_THREE_ONLY(false, true, false, false),
    /** 자유 오목 — 금수 없음, 5 이상. */
    FREESTYLE(false, false, false, false);

    private final boolean blackExactFive;
    private final boolean forbidsDoubleThree;
    private final boolean forbidsDoubleFour;
    private final boolean forbidsOverline;

    OmokRule(boolean blackExactFive, boolean forbidsDoubleThree, boolean forbidsDoubleFour, boolean forbidsOverline) {
        this.blackExactFive = blackExactFive;
        this.forbidsDoubleThree = forbidsDoubleThree;
        this.forbidsDoubleFour = forbidsDoubleFour;
        this.forbidsOverline = forbidsOverline;
    }

    /** 흑이 정확히 5 로만 이기는가(렌주). 아니면 5 이상. */
    public boolean blackExactFive() {
        return blackExactFive;
    }

    public boolean forbidsDoubleThree() {
        return forbidsDoubleThree;
    }

    public boolean forbidsDoubleFour() {
        return forbidsDoubleFour;
    }

    public boolean forbidsOverline() {
        return forbidsOverline;
    }

    /** 흑에게 금수가 하나라도 있는가 — 없으면 금수 계산을 통째로 건너뛴다. */
    public boolean hasForbiddenMoves() {
        return forbidsDoubleThree || forbidsDoubleFour || forbidsOverline;
    }
}
