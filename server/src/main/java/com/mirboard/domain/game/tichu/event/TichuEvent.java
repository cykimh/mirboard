package com.mirboard.domain.game.tichu.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.Team;
import java.util.List;
import java.util.Map;

/**
 * 티츄 엔진이 발행하는 이벤트 sealed 계층. Phase 4 의 STOMP 브로드캐스트가 본
 * 계층을 그대로 직렬화해서 envelope 의 payload 로 사용한다. Phase 5b 에서
 * Dealing/Passing 라이프사이클 이벤트들이 추가됨.
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
        @JsonSubTypes.Type(value = TichuEvent.RoundEnded.class, name = "ROUND_ENDED"),
        @JsonSubTypes.Type(value = TichuEvent.DealingPhaseStarted.class, name = "DEALING_PHASE_STARTED"),
        @JsonSubTypes.Type(value = TichuEvent.PlayerReady.class, name = "PLAYER_READY"),
        @JsonSubTypes.Type(value = TichuEvent.PassingStarted.class, name = "PASSING_STARTED"),
        @JsonSubTypes.Type(value = TichuEvent.PassingSubmitted.class, name = "PASSING_SUBMITTED"),
        @JsonSubTypes.Type(value = TichuEvent.CardsPassed.class, name = "CARDS_PASSED"),
        @JsonSubTypes.Type(value = TichuEvent.PlayingStarted.class, name = "PLAYING_STARTED"),
        @JsonSubTypes.Type(value = TichuEvent.RoundStarted.class, name = "ROUND_STARTED"),
        @JsonSubTypes.Type(value = TichuEvent.MatchEnded.class, name = "MATCH_ENDED")
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
                TichuEvent.RoundEnded,
                TichuEvent.DealingPhaseStarted,
                TichuEvent.PlayerReady,
                TichuEvent.PassingStarted,
                TichuEvent.PassingSubmitted,
                TichuEvent.CardsPassed,
                TichuEvent.PlayingStarted,
                TichuEvent.RoundStarted,
                TichuEvent.MatchEnded {

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
            case DealingPhaseStarted __ -> "DEALING_PHASE_STARTED";
            case PlayerReady __ -> "PLAYER_READY";
            case PassingStarted __ -> "PASSING_STARTED";
            case PassingSubmitted __ -> "PASSING_SUBMITTED";
            case CardsPassed __ -> "CARDS_PASSED";
            case PlayingStarted __ -> "PLAYING_STARTED";
            case RoundStarted __ -> "ROUND_STARTED";
            case MatchEnded __ -> "MATCH_ENDED";
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

    /**
     * 비공개 이벤트 — 본인 큐로만 전송. phaseCardCount 는 8 (Dealing 첫 분배)
     * 또는 14 (Dealing 두 번째 분배 / Passing 끝 직후 재분배).
     */
    record HandDealt(int seat, List<Card> cards, int phaseCardCount) implements TichuEvent {
        public HandDealt {
            cards = List.copyOf(cards);
        }
    }

    record RoundEnded(RoundScore score) implements TichuEvent {
    }

    /** Dealing 단계 시작/전환 공개 알림. phaseCardCount 는 8 또는 14. */
    record DealingPhaseStarted(int phaseCardCount) implements TichuEvent {
    }

    /** 좌석이 선언/스킵을 마쳤다는 공개 알림 (어떤 결정인지는 declarations 로 분리 공개). */
    record PlayerReady(int seat) implements TichuEvent {
    }

    /** Passing 단계 진입 공개 알림. */
    record PassingStarted() implements TichuEvent {
    }

    /** 좌석이 PassCards 제출을 마침. 실제 카드 정보는 공개되지 않음. */
    record PassingSubmitted(int seat) implements TichuEvent {
    }

    /** 4명 모두 PassCards 제출 후 동시 스왑 완료 공개 알림. 카드 자체는 비공개 큐 (HandDealt) 로. */
    record CardsPassed() implements TichuEvent {
    }

    /** Playing 단계 진입 공개 알림. Mahjong 보유자가 leadSeat 으로 첫 트릭 리드. */
    record PlayingStarted(int leadSeat) implements TichuEvent {
    }

    /** 새 라운드 진입 — Phase 5c 의 멀티 라운드 전환 공개 알림. */
    record RoundStarted(int roundNumber,
                        Map<Team, Integer> cumulativeScores) implements TichuEvent {
        public RoundStarted {
            cumulativeScores = Map.copyOf(cumulativeScores);
        }
    }

    /** 매치 종료 (1000점 도달) 공개 알림. 동점일 때는 발행하지 않고 한 라운드 더 진행. */
    record MatchEnded(Team winningTeam,
                      Map<Team, Integer> finalScores,
                      int roundsPlayed) implements TichuEvent {
        public MatchEnded {
            finalScores = Map.copyOf(finalScores);
        }
    }
}
