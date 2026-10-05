package com.mirboard.domain.game.onecard.event;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import java.util.List;

/**
 * 원카드 엔진이 발행하는 이벤트 sealed 계층 (설계서 §4.3).
 *
 * <p><b>payload 는 증감이 아니라 결과값을 싣는다</b> — 좌석 손패 장수, 공격 누적, 뽑을 더미 장수 등.
 * 잠금 없는 resync 와 겹쳐 같은 이벤트가 두 번 적용되거나 하나가 빠져도 다음 이벤트에서 맞춰진다.
 *
 * <p><b>State Hiding (D-01).</b> 손패 카드는 비공개 {@link HandDealt}·{@link HandUpdated} 에만 담긴다.
 * 이 둘은 손패 전체와 {@code handVersion} 을 실어, 클라가 더 낮은 버전을 버리면 순서가 뒤바뀌어도 손패가
 * 되돌아가지 않는다. 봇의 반응 시각(경쟁 창의 {@code botPress})은 어떤 이벤트에도 싣지 않는다.
 *
 * <p>{@code isGetterVisibility=NONE}: core {@code GameEvent.isPrivate()} 가 is-getter 라 그대로 두면 모든
 * 이벤트 JSON 에 {@code "private"} 키가 흘러나간다. 라우팅 판정은 서버 안의 일이라 클라 payload 에 필요 없다.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OneCardEvent.MatchStarted.class, name = "MATCH_STARTED"),
        @JsonSubTypes.Type(value = OneCardEvent.HandDealt.class, name = "HAND_DEALT"),
        @JsonSubTypes.Type(value = OneCardEvent.HandUpdated.class, name = "HAND_UPDATED"),
        @JsonSubTypes.Type(value = OneCardEvent.CardPlayed.class, name = "CARD_PLAYED"),
        @JsonSubTypes.Type(value = OneCardEvent.CardsDrawn.class, name = "CARDS_DRAWN"),
        @JsonSubTypes.Type(value = OneCardEvent.PileReshuffled.class, name = "PILE_RESHUFFLED"),
        @JsonSubTypes.Type(value = OneCardEvent.TurnChanged.class, name = "TURN_CHANGED"),
        @JsonSubTypes.Type(value = OneCardEvent.RaceOpened.class, name = "RACE_OPENED"),
        @JsonSubTypes.Type(value = OneCardEvent.RaceResolved.class, name = "RACE_RESOLVED"),
        @JsonSubTypes.Type(value = OneCardEvent.PlayerEliminated.class, name = "PLAYER_ELIMINATED"),
        @JsonSubTypes.Type(value = OneCardEvent.MatchEnded.class, name = "MATCH_ENDED")
})
public sealed interface OneCardEvent extends GameEvent
        permits OneCardEvent.MatchStarted,
                OneCardEvent.HandDealt,
                OneCardEvent.HandUpdated,
                OneCardEvent.CardPlayed,
                OneCardEvent.CardsDrawn,
                OneCardEvent.PileReshuffled,
                OneCardEvent.TurnChanged,
                OneCardEvent.RaceOpened,
                OneCardEvent.RaceResolved,
                OneCardEvent.PlayerEliminated,
                OneCardEvent.MatchEnded {

    @Override
    default String envelopeType() {
        return switch (this) {
            case MatchStarted __ -> "MATCH_STARTED";
            case HandDealt __ -> "HAND_DEALT";
            case HandUpdated __ -> "HAND_UPDATED";
            case CardPlayed __ -> "CARD_PLAYED";
            case CardsDrawn __ -> "CARDS_DRAWN";
            case PileReshuffled __ -> "PILE_RESHUFFLED";
            case TurnChanged __ -> "TURN_CHANGED";
            case RaceOpened __ -> "RACE_OPENED";
            case RaceResolved __ -> "RACE_RESOLVED";
            case PlayerEliminated __ -> "PLAYER_ELIMINATED";
            case MatchEnded __ -> "MATCH_ENDED";
        };
    }

    /** 손패를 담은 두 이벤트만 비공개다. */
    @Override
    default int privateSeat() {
        return switch (this) {
            case HandDealt dealt -> dealt.seat();
            case HandUpdated updated -> updated.seat();
            default -> -1;
        };
    }

    /**
     * D-129 — 손패를 담은 비공개 이벤트는 방 순번을 쓰지 않는다(D-126 의 포트 확장). 쓰면 그 이벤트를 받지 않는
     * 좌석에는 다음 공개 이벤트가 구멍으로 보인다 — 원카드는 내거나 먹을 때마다 {@code HAND_UPDATED} 가 나가므로
     * 매 차례 전원이 resync 했다(설계서 §4.5b).
     */
    @Override
    default boolean sequenced() {
        return !isPrivate();
    }

    /** 먹은 이유 (§6.3, §7, §9.1). */
    enum DrawReason {
        TURN,
        ATTACK,
        PENALTY
    }

    /** 경쟁 결과 (§9-4). {@code CANCELLED} 는 창 중 탈주로 벌칙 없이 닫힌 것(§9-7). */
    enum RaceOutcome {
        CALLED,
        CAUGHT,
        EXPIRED,
        CANCELLED
    }

    /** 공개 — 분배 직후의 테이블. */
    record MatchStarted(int firstSeat, PlayingCard startCard, int handSize, int drawPileCount)
            implements OneCardEvent {
    }

    /** 비공개 — 처음 받은 손패. */
    record HandDealt(int seat, List<PlayingCard> hand, int handVersion) implements OneCardEvent {
        public HandDealt {
            hand = List.copyOf(hand);
        }
    }

    /**
     * 비공개 — 손패가 바뀔 때마다(내기·먹기·벌칙·탈락) 손패 전체를 다시 보낸다.
     *
     * @param received 이번에 새로 받은 카드(애니메이션용). 내기·탈락이면 빈 목록
     */
    record HandUpdated(int seat, List<PlayingCard> hand, List<PlayingCard> received, int handVersion)
            implements OneCardEvent {
        public HandUpdated {
            hand = List.copyOf(hand);
            received = List.copyOf(received);
        }
    }

    /**
     * 공개 — 카드 한 장을 냈다.
     *
     * @param handCount   낸 뒤 그 좌석의 손패 장수
     * @param attackStack 낸 뒤의 공격 누적
     * @param direction   낸 뒤 방향 — Q 로 창이 열리면 TURN_CHANGED 가 늦으므로 여기서 알린다
     */
    record CardPlayed(int seat, PlayingCard card, Suit declaredSuit, int handCount, int attackStack, int direction)
            implements OneCardEvent {
    }

    /**
     * 공개 — 카드를 먹었다(장수만).
     *
     * @param count         실제로 먹은 장수(더미가 모자라면 요구보다 적다)
     * @param handCount     먹은 뒤 그 좌석의 손패 장수
     * @param drawPileCount 먹은 뒤 뽑을 더미 장수
     */
    record CardsDrawn(int seat, int count, DrawReason reason, int handCount, int drawPileCount)
            implements OneCardEvent {
    }

    /** 공개 — 버린 더미를 섞어 뽑을 더미를 다시 채웠다(§7.1). */
    record PileReshuffled(int drawPileCount) implements OneCardEvent {
    }

    /** 공개 — 차례가 넘어갔다. {@code attackStack} 이 0 보다 크면 그 좌석이 공격받는 중이다. */
    record TurnChanged(int seat, int direction, int attackStack) implements OneCardEvent {
    }

    /** 공개 — 경쟁 창이 열렸다. 봇의 반응 시각은 싣지 않는다. */
    record RaceOpened(int raceId, int ownerSeat, int slot, int jitterX, int jitterY, long windowMillis)
            implements OneCardEvent {
    }

    /** 공개 — 경쟁 창이 닫혔다. {@code bySeat} 는 누른 좌석, 아무도 안 눌렀으면 −1. */
    record RaceResolved(int raceId, RaceOutcome outcome, int bySeat) implements OneCardEvent {
    }

    /**
     * 공개 — 탈락.
     *
     * @param cardsHeld     탈락 순간 손에 있던 장수
     * @param drawPileCount 탈락자 손패를 맨 아래에 넣은 뒤 뽑을 더미 장수(결과값). 파산이면 같은 전이
     *                      CARDS_DRAWN 의 값보다 크고 이쪽이 최종이다
     */
    record PlayerEliminated(int seat, Elimination.Reason reason, int cardsHeld, int drawPileCount)
            implements OneCardEvent {
    }

    /** 공개 — 매치 종료와 순위. */
    record MatchEnded(MatchResult.EndReason reason, List<MatchResult.Standing> standings)
            implements OneCardEvent {
        public MatchEnded {
            standings = List.copyOf(standings);
        }
    }
}
