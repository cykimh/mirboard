package com.mirboard.domain.game.omok.rules;

/**
 * 금수 종류 (룰 §6). 한 자리가 여러 종류에 해당하면 장목 → 44 → 33 순으로 하나만 적는다(룰 §6.1). 이름은 클라와의 계약이다.
 */
public enum ForbiddenKind {
    DOUBLE_THREE,
    DOUBLE_FOUR,
    OVERLINE
}
