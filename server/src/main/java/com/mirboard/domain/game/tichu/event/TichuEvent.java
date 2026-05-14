package com.mirboard.domain.game.tichu.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import java.util.List;

/**
 * 티츄 엔진이 발행하는 이벤트 sealed 계층. Phase 4 의 STOMP 브로드캐스트가 본
 * 계층을 그대로 직렬화해서 envelope 의 payload 로 사용한다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TichuEvent.Played.class, name = "PLAYED"),
        @JsonSubTypes.Type(value = TichuEvent.Passed.class, name = "PASSED"),
        @JsonSubTypes.Type(value = TichuEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = TichuEvent.TrickTaken.class, name = "TRICK_TAKEN"),
        @JsonSubTypes.Type(value = TichuEvent.TichuDeclared.class, name = "TICHU_DECLARED"),
        @JsonSubTypes.Type(value = TichuEvent.WishMade.class, name = "WISH_MADE"),
        @JsonSubTypes.Type(value = TichuEvent.DragonGiven.class, name = "DRAGON_GIVEN"),
        @JsonSubTypes.Type(value = TichuEvent.PlayerFinished.class, name = "PLAYER_FINISHED"),
        @JsonSubTypes.Type(value = TichuEvent.HandDealt.class, name = "HAND_DEALT"),
        @JsonSubTypes.Type(value = TichuEvent.RoundEnded.class, name = "ROUND_ENDED")
})
public sealed interface TichuEvent extends GameEvent
        permits TichuEvent.Played,
                TichuEvent.Passed,
                TichuEvent.TurnChanged,
                TichuEvent.TrickTaken,
                TichuEvent.TichuDeclared,
                TichuEvent.WishMade,
                TichuEvent.DragonGiven,
                TichuEvent.PlayerFinished,
                TichuEvent.HandDealt,
                TichuEvent.RoundEnded {

    /** envelope `type` 필드용 안정 식별자. `@JsonSubTypes` 이름과 일치. */
    default String envelopeType() {
        return switch (this) {
            case Played __ -> "PLAYED";
            case Passed __ -> "PASSED";
            case TurnChanged __ -> "TURN_CHANGED";
            case TrickTaken __ -> "TRICK_TAKEN";
            case TichuDeclared __ -> "TICHU_DECLARED";
            case WishMade __ -> "WISH_MADE";
            case DragonGiven __ -> "DRAGON_GIVEN";
            case PlayerFinished __ -> "PLAYER_FINISHED";
            case HandDealt __ -> "HAND_DEALT";
            case RoundEnded __ -> "ROUND_ENDED";
        };
    }

    /** 본인 큐로만 보내야 하는 비공개 이벤트 여부. */
    default boolean isPrivate() {
        return this instanceof HandDealt;
    }

    record Played(int seat, Hand hand) implements TichuEvent {
    }

    record Passed(int seat) implements TichuEvent {
    }

    record TurnChanged(int currentTurnSeat) implements TichuEvent {
    }

    record TrickTaken(int takerSeat, int trickPoints) implements TichuEvent {
    }

    record TichuDeclared(int seat, TichuDeclaration kind) implements TichuEvent {
    }

    record WishMade(int rank) implements TichuEvent {
    }

    record DragonGiven(int fromSeat, int toSeat) implements TichuEvent {
    }

    record PlayerFinished(int seat, int order) implements TichuEvent {
    }

    /** 비공개 이벤트 — 본인 큐로만 전송. */
    record HandDealt(int seat, List<Card> cards) implements TichuEvent {
        public HandDealt {
            cards = List.copyOf(cards);
        }
    }

    record RoundEnded(RoundScore score) implements TichuEvent {
    }
}
