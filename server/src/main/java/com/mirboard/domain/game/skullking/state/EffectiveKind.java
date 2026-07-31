package com.mirboard.domain.game.skullking.state;

/**
 * 트릭에 제출된 카드가 <b>판정에서 실제로 무엇으로 취급되는가</b>.
 *
 * <p>이 타입이 존재하는 이유는 티그리스 하나다. 명세 함정 #10:
 * <i>"티그리스는 선언값이 곧 정체성 — 강약·동점·보너스 세 군데 모두에서 선언한 카드로
 * 취급해야 한다. 한 군데만 빠뜨리면 조용히 어긋난다."</i>
 *
 * <p>세 곳이 각자 {@code if (티그리스 && 해적선언)} 을 쓰면 반드시 어긋나므로,
 * {@link PlayedCard#kind()} 가 제출 시점에 <b>한 번</b> 해소한 값을 단일 출처로 둔다.
 * {@code TrickResolver}·동점 처리·{@code BonusCalculator} 는 전부 이 값만 읽고,
 * 그 뒤로는 아무도 티그리스를 모른다.
 */
public enum EffectiveKind {

    /** 색상 카드 — 검정 포함. 서열은 §7.1 로 따로 판정한다. */
    SUIT,

    /** 탈출 (또는 탈출 선언 티그리스). 무조건 패배 (§7.2). */
    ESCAPE,

    /** 인어 — 스컬킹을 이기고 해적에게 진다. */
    MERMAID,

    /** 해적 (또는 해적 선언 티그리스) — 인어를 이기고 스컬킹에게 진다. */
    PIRATE,

    /** 스컬킹 — 해적을 이기고 인어에게 진다. */
    SKULL_KING;

    /**
     * 캐릭터 카드인가 (§6.1). 캐릭터로 리드하면 그 트릭엔 리드 수트가 <b>없다</b> —
     * 탈출 리드(확정 보류)와 구분되는 지점이라 {@code LeadSuitResolver} 가 이 값을 읽는다.
     */
    public boolean isCharacter() {
        return this == MERMAID || this == PIRATE || this == SKULL_KING;
    }
}
