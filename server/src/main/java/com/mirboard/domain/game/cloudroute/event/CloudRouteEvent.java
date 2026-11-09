package com.mirboard.domain.game.cloudroute.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.MatchResult;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.core.GameEvent;
import java.util.List;

/**
 * 구름항로 엔진이 발행하는 이벤트 sealed 계층 (설계서 `docs/plans/property-board.md` §8.3).
 *
 * <p><b>전부 공개</b>다 — 손패 같은 개인 정보가 없다(룰 §13). 숨기는 것은 편지 더미 순서뿐이라 {@link LetterDrawn} 은 남은 장수만
 * 싣는다. payload 는 증감이 아니라 <b>결과값</b>(뒤 현금 등)을 싣고, 공개 상태가 바뀌는 곳마다 이벤트가 있다 — resync 에 기대지
 * 않는다(D-126 교훈). 시뮬레이션의 이벤트 거울이 이 약속을 매 전이 대조한다.
 *
 * <p>{@code isGetterVisibility=NONE}: core {@code GameEvent.isPrivate()} 가 JSON 에 새지 않게(원카드와 같음).
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CloudRouteEvent.DiceRolled.class, name = "DICE_ROLLED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.TokenMoved.class, name = "TOKEN_MOVED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.LandBought.class, name = "LAND_BOUGHT"),
        @JsonSubTypes.Type(value = CloudRouteEvent.Built.class, name = "BUILT"),
        @JsonSubTypes.Type(value = CloudRouteEvent.TollPaid.class, name = "TOLL_PAID"),
        @JsonSubTypes.Type(value = CloudRouteEvent.TakenOver.class, name = "TAKEN_OVER"),
        @JsonSubTypes.Type(value = CloudRouteEvent.LetterDrawn.class, name = "LETTER_DRAWN"),
        @JsonSubTypes.Type(value = CloudRouteEvent.MoneyChanged.class, name = "MONEY_CHANGED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.MoneyTransferred.class, name = "MONEY_TRANSFERRED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.WhirlpoolEntered.class, name = "WHIRLPOOL_ENTERED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.EscapeFailed.class, name = "ESCAPE_FAILED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.WhirlpoolLeft.class, name = "WHIRLPOOL_LEFT"),
        @JsonSubTypes.Type(value = CloudRouteEvent.FestivalHosted.class, name = "FESTIVAL_HOSTED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.FestivalCleared.class, name = "FESTIVAL_CLEARED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.AssetsLiquidated.class, name = "ASSETS_LIQUIDATED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.PlayerEliminated.class, name = "PLAYER_ELIMINATED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.DecisionRequired.class, name = "DECISION_REQUIRED"),
        @JsonSubTypes.Type(value = CloudRouteEvent.MatchEnded.class, name = "MATCH_ENDED")
})
public sealed interface CloudRouteEvent extends GameEvent
        permits CloudRouteEvent.DiceRolled, CloudRouteEvent.TokenMoved, CloudRouteEvent.LandBought,
                CloudRouteEvent.Built, CloudRouteEvent.TollPaid, CloudRouteEvent.TakenOver,
                CloudRouteEvent.LetterDrawn, CloudRouteEvent.MoneyChanged, CloudRouteEvent.MoneyTransferred,
                CloudRouteEvent.WhirlpoolEntered, CloudRouteEvent.EscapeFailed, CloudRouteEvent.WhirlpoolLeft,
                CloudRouteEvent.FestivalHosted, CloudRouteEvent.FestivalCleared, CloudRouteEvent.AssetsLiquidated,
                CloudRouteEvent.PlayerEliminated, CloudRouteEvent.TurnChanged, CloudRouteEvent.DecisionRequired,
                CloudRouteEvent.MatchEnded {

    @Override
    default String envelopeType() {
        return switch (this) {
            case DiceRolled __ -> "DICE_ROLLED";
            case TokenMoved __ -> "TOKEN_MOVED";
            case LandBought __ -> "LAND_BOUGHT";
            case Built __ -> "BUILT";
            case TollPaid __ -> "TOLL_PAID";
            case TakenOver __ -> "TAKEN_OVER";
            case LetterDrawn __ -> "LETTER_DRAWN";
            case MoneyChanged __ -> "MONEY_CHANGED";
            case MoneyTransferred __ -> "MONEY_TRANSFERRED";
            case WhirlpoolEntered __ -> "WHIRLPOOL_ENTERED";
            case EscapeFailed __ -> "ESCAPE_FAILED";
            case WhirlpoolLeft __ -> "WHIRLPOOL_LEFT";
            case FestivalHosted __ -> "FESTIVAL_HOSTED";
            case FestivalCleared __ -> "FESTIVAL_CLEARED";
            case AssetsLiquidated __ -> "ASSETS_LIQUIDATED";
            case PlayerEliminated __ -> "PLAYER_ELIMINATED";
            case TurnChanged __ -> "TURN_CHANGED";
            case DecisionRequired __ -> "DECISION_REQUIRED";
            case MatchEnded __ -> "MATCH_ENDED";
        };
    }

    /** 이동 방식. */
    enum MoveKind {
        /** 주사위. */
        ROLL,
        /** 바람 정류장 비행. */
        FLY,
        /** 편지(순풍·맞바람·등 떠미는 바람). */
        LETTER,
        /** 소용돌이로 보내짐(세 번째 더블·소용돌이 경보). */
        WHIRLPOOL
    }

    /** 은행과 오간 돈의 사유. */
    enum MoneyReason {
        CUSTOMS,
        LETTER,
        ESCAPE,
        FLIGHT
    }

    /** 사람 사이에 오간 돈의 사유(통행료·인수는 따로). */
    enum TransferReason {
        ISLAND_FEAST,
        WIND_SHARE
    }

    /** 소용돌이에서 나온 방법(§7.2). */
    enum LeaveMethod {
        DOUBLES,
        PAID,
        BUOY,
        SERVED
    }

    /** @param doublesThisTurn 이 굴림을 센 뒤의 연속 더블 수(소용돌이 굴림은 세지 않아 0) */
    record DiceRolled(int seat, int die1, int die2, boolean doubles, int doublesThisTurn) implements CloudRouteEvent {
    }

    /** @param salary 이 이동으로 받은 급여(0 또는 200) @param cash 이동 뒤 현금 */
    record TokenMoved(int seat, int from, int to, MoveKind kind, int salary, int cash) implements CloudRouteEvent {
    }

    record LandBought(int seat, int tile, int level, int paid, int cash) implements CloudRouteEvent {
    }

    record Built(int seat, int tile, int level, int paid, int cash) implements CloudRouteEvent {
    }

    /** @param amount 실제로 낸 돈(파산이면 남은 현금 전부) */
    record TollPaid(int payer, int owner, int tile, int amount, boolean monopoly, boolean festival,
                    int payerCash, int ownerCash) implements CloudRouteEvent {
    }

    record TakenOver(int buyer, int seller, int tile, int price, int buyerCash, int sellerCash)
            implements CloudRouteEvent {
    }

    /** @param deckCount 뽑은 뒤 더미 남은 장수 @param reshuffled 뽑기 전에 버린 더미를 섞어 새 더미로 썼는가 */
    record LetterDrawn(int seat, Letter letter, int deckCount, boolean reshuffled) implements CloudRouteEvent {
    }

    /** 은행과의 돈. @param delta 받으면 +, 내면 − */
    record MoneyChanged(int seat, int delta, MoneyReason reason, int cash) implements CloudRouteEvent {
    }

    record MoneyTransferred(int from, int to, int amount, TransferReason reason, int fromCash, int toCash)
            implements CloudRouteEvent {
    }

    record WhirlpoolEntered(int seat) implements CloudRouteEvent {
    }

    record EscapeFailed(int seat, int failedEscapes) implements CloudRouteEvent {
    }

    record WhirlpoolLeft(int seat, LeaveMethod method) implements CloudRouteEvent {
    }

    record FestivalHosted(int seat, int tile) implements CloudRouteEvent {
    }

    /** 축제가 꺼졌다(새 축제가 아니라 팔림·인수·탈락으로). */
    record FestivalCleared(int tile) implements CloudRouteEvent {
    }

    /** 자동 청산(§10). @param cash 청산 뒤, 지불 전 현금 */
    record AssetsLiquidated(int seat, List<Sale> sales, int cash) implements CloudRouteEvent {
        public AssetsLiquidated {
            sales = List.copyOf(sales);
        }

        /** @param land true 면 땅을 팔아 주인 없는 땅이 됐다, false 면 건물 한 단계를 팔아 {@code levelAfter} 가 됐다 */
        public record Sale(int tile, boolean land, int levelAfter, int received) {
        }
    }

    /**
     * @param creditor      파산이면 돈을 받은 좌석(은행이면 −1), 탈주면 −1
     * @param returnedTiles 은행으로 돌아간 땅
     * @param cashForfeited 탈주로 사라진 현금(파산은 이미 다 냈으므로 0)
     */
    record PlayerEliminated(int seat, Elimination.Reason reason, int creditor, List<Integer> returnedTiles,
                            int cashForfeited) implements CloudRouteEvent {
        public PlayerEliminated {
            returnedTiles = List.copyOf(returnedTiles);
        }
    }

    record TurnChanged(int seat, int round) implements CloudRouteEvent {
    }

    /** 차례 좌석이 내려야 할 다음 결정. 결정이 바뀔 때마다 나간다. */
    record DecisionRequired(int seat, Pending pending) implements CloudRouteEvent {
    }

    record MatchEnded(MatchResult result) implements CloudRouteEvent {
    }
}
