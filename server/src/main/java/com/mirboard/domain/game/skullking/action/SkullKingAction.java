package com.mirboard.domain.game.skullking.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.TigressMode;

/**
 * 스컬킹 액션 sealed 계층 (`docs/rules-skullking.md` §3). 모든 변형이 본 파일 안에 nested
 * record 로 존재해 패턴 매칭의 누락 케이스를 컴파일러가 강제 검출한다.
 *
 * <p>티츄와 달리 액션이 둘뿐이다 — 스컬킹에는 패스도, 선언도, 카드 교환도 없다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SkullKingAction.PlaceBid.class, name = "PLACE_BID"),
        @JsonSubTypes.Type(value = SkullKingAction.PlayCard.class, name = "PLAY_CARD")
})
public sealed interface SkullKingAction extends GameAction
        permits SkullKingAction.PlaceBid, SkullKingAction.PlayCard {

    /**
     * 승수 예측 (§5). 범위는 {@code 0 ~ handSize} — 라운드 번호가 아니라 손패 장수다
     * (§13-⑪). 제출 후 변경 불가.
     */
    record PlaceBid(int bid) implements SkullKingAction {
    }

    /**
     * 카드 한 장 제출 (§6). 티츄와 달리 묶음이 아니라 단수다.
     *
     * @param declaredAs 티그리스일 때만 채운다 (해적/탈출). 다른 카드에 실으면 거절
     */
    record PlayCard(SkullCard card, TigressMode declaredAs) implements SkullKingAction {

        public static PlayCard of(SkullCard card) {
            return new PlayCard(card, null);
        }

        public static PlayCard tigress(TigressMode mode) {
            return new PlayCard(SkullCard.tigress(), mode);
        }
    }
}
