package com.mirboard.domain.game.onecard.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;

/**
 * 원카드 액션 sealed 계층 — 클라가 보낼 수 있는 것 전부(설계서 §4.3).
 *
 * <p>"창 닫기" 같은 시스템 전이는 여기 없다. 클라 JSON 으로 역직렬화되는 계층에 넣으면 클라가 위조해 보낼
 * 수 있으므로, 시간에 따른 전이는 엔진의 {@code onTimer} 가 따로 맡는다(설계서 §4.5).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OneCardAction.PlayCard.class, name = "PLAY_CARD"),
        @JsonSubTypes.Type(value = OneCardAction.Draw.class, name = "DRAW"),
        @JsonSubTypes.Type(value = OneCardAction.CallOneCard.class, name = "CALL_ONE_CARD"),
        @JsonSubTypes.Type(value = OneCardAction.Catch.class, name = "CATCH")
})
public sealed interface OneCardAction extends GameAction
        permits OneCardAction.PlayCard, OneCardAction.Draw, OneCardAction.CallOneCard, OneCardAction.Catch {

    /**
     * 카드 한 장 내기 (§5).
     *
     * @param declaredSuit 7 을 낼 때만 채운다(§8.1). 다른 카드에 실으면 거절
     */
    record PlayCard(PlayingCard card, Suit declaredSuit) implements OneCardAction {

        public static PlayCard of(PlayingCard card) {
            return new PlayCard(card, null);
        }
    }

    /** 먹기 (§7) — 공격받는 중이면 누적 장수, 아니면 1장. */
    record Draw() implements OneCardAction {
    }

    /** "원카드!" — 창 주인만 (§9). */
    record CallOneCard(int raceId) implements OneCardAction {
    }

    /** "잡기!" — 창 주인이 아닌 살아 있는 사람 (§9). */
    record Catch(int raceId) implements OneCardAction {
    }
}
