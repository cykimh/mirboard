package com.mirboard.domain.game.skullking.card;

/**
 * 특수 카드 14장의 종류 (`docs/rules-skullking.md` §1).
 *
 * <p>{@link #TIGRESS} 만 정체성이 고정돼 있지 않다 — 낼 때 해적/탈출 중 하나를 선언하며,
 * 그 선언값이 강약·동점·보너스 <b>세 군데 모두</b>에서 정체성이 된다 (§13-②③⑩). 그래서
 * 본 enum 은 "덱에 무엇이 들어 있는가"만 표현하고, 판정에 쓰이는 값은
 * {@code PlayedCard.kind()} 가 따로 해소한다.
 */
public enum SpecialKind {

    /** 해적 5장. 색상 카드와 인어를 이기고 스컬킹에게 진다. */
    PIRATE(5),

    /** 인어 2장. 색상 카드와 스컬킹을 이기고 해적에게 진다. */
    MERMAID(2),

    /** 스컬킹 1장. 색상 카드와 해적을 이기고 인어에게 진다. */
    SKULL_KING(1),

    /** 티그리스 1장. 낼 때 해적/탈출 중 선언 (`TigressMode`). */
    TIGRESS(1),

    /** 탈출 5장. 무조건 패배 — 전원 탈출일 때만 먼저 낸 쪽이 가져간다. */
    ESCAPE(5);

    private final int countInDeck;

    SpecialKind(int countInDeck) {
        this.countInDeck = countInDeck;
    }

    /** 덱에 들어가는 장수. {@code Deck} 조립과 카드 보존 불변식이 함께 읽는다. */
    public int countInDeck() {
        return countInDeck;
    }
}
