package com.mirboard.domain.game.skullking.card;

/**
 * 티그리스를 낼 때 선언하는 정체성 (`docs/rules-skullking.md` §1, §13-②③⑩).
 *
 * <p>티그리스가 아닌 카드에는 붙지 않는다 — 액션이 다른 카드에 이 값을 실어 보내면
 * {@code ActionValidator} 가 거절한다.
 */
public enum TigressMode {

    /** 해적으로 선언 — 강약·동점·포획 보너스 모두에서 해적 1장으로 센다. */
    PIRATE,

    /** 탈출로 선언 — "전원 탈출" 판정에도 탈출로 포함된다 (§13-④). */
    ESCAPE
}
