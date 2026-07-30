package com.mirboard.domain.game.skullking.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import java.util.List;
import java.util.Map;

/**
 * 스컬킹 엔진이 발행하는 이벤트 sealed 계층.
 *
 * <p><b>State Hiding 경계 (§5, D-01).</b> 스컬킹의 은닉 정보는 티츄보다 좁다 — 손패와
 * <i>제출 전</i> 예측값뿐이고, 전원 제출 후의 예측 승수·획득 트릭 수는 공개다. 그래서
 * 입찰이 두 이벤트로 갈린다:
 * <ul>
 *   <li>{@link BidSubmitted} — "냈다"는 사실만. <b>값이 없다</b></li>
 *   <li>{@link BidsRevealed} — 전원 제출 후 한꺼번에 값 공개</li>
 * </ul>
 * 남의 예측을 보고 내 예측을 정할 수 없어야 하므로 (원문은 주먹을 세 번 두드리고 동시에
 * 손가락을 세우는 방식) 이 분리가 룰 요구사항이다. 값을 하나의 이벤트에 담으면 순차
 * 입력이 곧 순차 공개가 되어 룰이 깨진다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SkullKingEvent.HandDealt.class, name = "HAND_DEALT"),
        @JsonSubTypes.Type(value = SkullKingEvent.BiddingStarted.class, name = "BIDDING_STARTED"),
        @JsonSubTypes.Type(value = SkullKingEvent.BidSubmitted.class, name = "BID_SUBMITTED"),
        @JsonSubTypes.Type(value = SkullKingEvent.BidsRevealed.class, name = "BIDS_REVEALED"),
        @JsonSubTypes.Type(value = SkullKingEvent.PlayingStarted.class, name = "PLAYING_STARTED"),
        @JsonSubTypes.Type(value = SkullKingEvent.CardPlayed.class, name = "CARD_PLAYED"),
        @JsonSubTypes.Type(value = SkullKingEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = SkullKingEvent.TrickTaken.class, name = "TRICK_TAKEN"),
        @JsonSubTypes.Type(value = SkullKingEvent.RoundEnded.class, name = "ROUND_ENDED"),
        @JsonSubTypes.Type(value = SkullKingEvent.MatchEnded.class, name = "MATCH_ENDED")
})
public sealed interface SkullKingEvent extends GameEvent
        permits SkullKingEvent.HandDealt,
                SkullKingEvent.BiddingStarted,
                SkullKingEvent.BidSubmitted,
                SkullKingEvent.BidsRevealed,
                SkullKingEvent.PlayingStarted,
                SkullKingEvent.CardPlayed,
                SkullKingEvent.TurnChanged,
                SkullKingEvent.TrickTaken,
                SkullKingEvent.RoundEnded,
                SkullKingEvent.MatchEnded {

    @Override
    default String envelopeType() {
        return switch (this) {
            case HandDealt __ -> "HAND_DEALT";
            case BiddingStarted __ -> "BIDDING_STARTED";
            case BidSubmitted __ -> "BID_SUBMITTED";
            case BidsRevealed __ -> "BIDS_REVEALED";
            case PlayingStarted __ -> "PLAYING_STARTED";
            case CardPlayed __ -> "CARD_PLAYED";
            case TurnChanged __ -> "TURN_CHANGED";
            case TrickTaken __ -> "TRICK_TAKEN";
            case RoundEnded __ -> "ROUND_ENDED";
            case MatchEnded __ -> "MATCH_ENDED";
        };
    }

    /** 손패만 비공개다. 예측값은 {@link BidsRevealed} 로 전원 제출 후 공개된다. */
    @Override
    default int privateSeat() {
        return this instanceof HandDealt hd ? hd.seat() : -1;
    }

    /** 비공개 — 본인 큐로만. 라운드마다 손패 장수가 달라진다 (§4). */
    record HandDealt(int seat, List<SkullCard> cards, int roundNumber) implements SkullKingEvent {
        public HandDealt {
            cards = List.copyOf(cards);
        }
    }

    /** 라운드 진입 + 입찰 단계 시작. {@code handSize} 가 곧 이 라운드의 트릭 수이자 예측 상한. */
    record BiddingStarted(int roundNumber, int handSize) implements SkullKingEvent {
    }

    /** 예측을 냈다는 사실만 공개 — 값은 담지 않는다 (§5 동시 공개). */
    record BidSubmitted(int seat) implements SkullKingEvent {
    }

    /** 전원 제출 완료 → 예측값 동시 공개. 이 시점부터 예측은 공개 정보다. */
    record BidsRevealed(Map<Integer, Integer> bids) implements SkullKingEvent {
        public BidsRevealed {
            bids = Map.copyOf(bids);
        }
    }

    /** 플레이 단계 진입. */
    record PlayingStarted(int leadSeat) implements SkullKingEvent {
    }

    /** 카드 제출 공개. 티그리스는 선언까지 공개된다 — 판정 근거라 숨기면 안 된다. */
    record CardPlayed(int seat, SkullCard card, TigressMode declaredAs)
            implements SkullKingEvent {
    }

    record TurnChanged(int currentTurnSeat) implements SkullKingEvent {
    }

    /**
     * 트릭 종료. 승자와 이긴 카드를 함께 싣는다 — 클라가 왜 이겼는지 표시할 수 있어야
     * 비추이적 순환(§7)이 납득 가능해진다.
     */
    record TrickTaken(int winnerSeat, SkullCard winningCard, int trickNumber)
            implements SkullKingEvent {
    }

    /** 라운드 종료 + 좌석별 점수 내역 + 매치 누적. */
    record RoundEnded(int roundNumber,
                      Map<Integer, RoundScore> scores,
                      Map<Integer, Integer> cumulativeScores) implements SkullKingEvent {
        public RoundEnded {
            scores = Map.copyOf(scores);
            cumulativeScores = Map.copyOf(cumulativeScores);
        }
    }

    /** 10라운드 종료 (§12). 동점이면 공동 승리라 {@code winners} 가 복수다 (§13-⑰). */
    record MatchEnded(List<Integer> winners,
                      Map<Integer, Integer> finalScores,
                      int roundsPlayed) implements SkullKingEvent {
        public MatchEnded {
            winners = List.copyOf(winners);
            finalScores = Map.copyOf(finalScores);
        }
    }
}
