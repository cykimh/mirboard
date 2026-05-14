package com.mirboard.domain.game.tichu.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.tichu.card.Card;
import java.util.List;

/**
 * 티츄 액션 sealed 계층. 모든 변형이 본 파일 안에 nested record 로 존재해 패턴
 * 매칭의 누락 케이스를 컴파일러가 강제 검출한다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TichuAction.DeclareGrandTichu.class, name = "DECLARE_GRAND_TICHU"),
        @JsonSubTypes.Type(value = TichuAction.DeclareTichu.class, name = "DECLARE_TICHU"),
        @JsonSubTypes.Type(value = TichuAction.PassCards.class, name = "PASS_CARDS"),
        @JsonSubTypes.Type(value = TichuAction.PlayCard.class, name = "PLAY_CARD"),
        @JsonSubTypes.Type(value = TichuAction.PassTrick.class, name = "PASS_TRICK"),
        @JsonSubTypes.Type(value = TichuAction.MakeWish.class, name = "MAKE_WISH"),
        @JsonSubTypes.Type(value = TichuAction.GiveDragonTrick.class, name = "GIVE_DRAGON_TRICK")
})
public sealed interface TichuAction extends GameAction
        permits TichuAction.DeclareGrandTichu,
                TichuAction.DeclareTichu,
                TichuAction.PassCards,
                TichuAction.PlayCard,
                TichuAction.PassTrick,
                TichuAction.MakeWish,
                TichuAction.GiveDragonTrick {

    /** 첫 8장 단계에서 한 번만 선언 가능 (성공/실패 ±200). */
    record DeclareGrandTichu() implements TichuAction {
    }

    /** 첫 카드를 내기 전까지 선언 가능 (성공/실패 ±100). */
    record DeclareTichu() implements TichuAction {
    }

    /** 14장 분배 후 좌/파트너/우 각 1장 패스. */
    record PassCards(Card toLeft, Card toPartner, Card toRight) implements TichuAction {
    }

    /** 차례에서 손패 묶음을 트릭에 낸다. 폭탄은 다른 차례에도 인터럽트 가능. */
    record PlayCard(List<Card> cards) implements TichuAction {
        public PlayCard {
            cards = List.copyOf(cards);
        }
    }

    /** 차례에서 패스. 리드 차례에는 패스 불가. */
    record PassTrick() implements TichuAction {
    }

    /** Mahjong 을 낸 직후, 한 번에 한해 2~14 중 한 rank 를 소원으로 지정. */
    record MakeWish(int rank) implements TichuAction {
    }

    /** Dragon 으로 트릭을 가져간 직후, 상대 팀 좌석 중 하나에게 트릭 양도. */
    record GiveDragonTrick(int toSeat) implements TichuAction {
    }
}
