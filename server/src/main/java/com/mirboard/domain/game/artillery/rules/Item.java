package com.mirboard.domain.game.artillery.rules;

import java.util.List;

/**
 * 아이템 (`docs/rules-artillery.md` §6.4, D-224). 판 시작 때 전원이 세 가지를 1개씩 받고, 한 차례에 하나를 {@code FIRE.item} 에 실어 쓴다.
 */
public enum Item {
    /** 물뿌리개 — 쏘기 전에 체력 +25(최대 체력까지). */
    WATERING_CAN,
    /** 바람개비 — 이번 발은 바람을 받지 않는다. */
    PINWHEEL,
    /** 겹씨앗 — 첫 발이 다 해소된 뒤 같은 각도·힘·탄으로 한 발 더(탄 수는 한 발만 쓴다). */
    TWIN_SEED;

    public static final int HEAL = 25;

    /** 판 시작 묶음. */
    public static List<Item> starterKit() {
        return List.of(values());
    }
}
