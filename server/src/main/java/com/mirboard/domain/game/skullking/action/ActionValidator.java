package com.mirboard.domain.game.skullking.action;

import com.mirboard.domain.game.skullking.bid.BidRules;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.List;
import java.util.Optional;

/**
 * 액션을 상태에 적용하기 전에 합법성을 검증한다 (`docs/rules-skullking.md` §5, §6.2).
 * 실패 시 {@link SkullKingActionRejectedException}. 본 클래스는 상태를 변경하지 않는다.
 */
public final class ActionValidator {

    private ActionValidator() {
    }

    public static void validate(SkullKingState state, int seat, SkullKingAction action) {
        requireSeatInRange(state, seat);
        switch (action) {
            case SkullKingAction.PlaceBid bid -> validatePlaceBid(state, seat, bid);
            case SkullKingAction.PlayCard play -> validatePlayCard(state, seat, play);
        }
    }

    // ---------- PLACE_BID (§5) ----------

    private static void validatePlaceBid(SkullKingState state, int seat,
                                         SkullKingAction.PlaceBid action) {
        SkullKingState.Bidding bidding = requireBidding(state);
        PlayerState player = bidding.players().get(seat);

        if (player.hasBid()) {
            throw reject(RejectionReason.ALREADY_BID, "seat " + seat);
        }
        if (!BidRules.isValid(action.bid(), player.handSize())) {
            throw reject(RejectionReason.BID_OUT_OF_RANGE,
                    "bid=" + action.bid() + " handSize=" + player.handSize());
        }
    }

    // ---------- PLAY_CARD (§6) ----------

    private static void validatePlayCard(SkullKingState state, int seat,
                                         SkullKingAction.PlayCard action) {
        SkullKingState.Playing playing = requirePlaying(state);
        TrickState trick = playing.trick();
        PlayerState player = playing.players().get(seat);

        if (trick.currentTurnSeat(playing.seatCount()) != seat) {
            throw reject(RejectionReason.NOT_YOUR_TURN,
                    "expected " + trick.currentTurnSeat(playing.seatCount()) + " got " + seat);
        }
        // null 을 여기서 먼저 거절한다 — 아래 hand().contains(null) 는 불변 리스트라 NPE 를
        // 던져 거절 코드가 클라에 나가지 못한다 (티츄의 HashMap 기반 검사와 다른 지점).
        if (action.card() == null) {
            throw reject(RejectionReason.CARD_NOT_OWNED, "card is null");
        }
        validateTigressDeclaration(action);
        if (!player.hand().contains(action.card())) {
            throw reject(RejectionReason.CARD_NOT_OWNED, String.valueOf(action.card()));
        }
        if (!followsLeadSuit(trick, player.hand(), action.card())) {
            throw reject(RejectionReason.MUST_FOLLOW_LEAD_SUIT,
                    "lead=" + trick.leadSuit().orElse(null) + " played=" + action.card());
        }
    }

    /**
     * 선언은 티그리스에만 붙는다. {@code PlayedCard} 생성자도 같은 불변식을 지키지만 거기서
     * 터지면 {@code IllegalArgumentException} 이라 클라에게 코드가 안 나간다 — 여기서
     * 먼저 잡아 {@link RejectionReason#INVALID_TIGRESS_DECLARATION} 로 되쏜다.
     */
    private static void validateTigressDeclaration(SkullKingAction.PlayCard action) {
        boolean isTigress = action.card() != null && action.card().is(SpecialKind.TIGRESS);
        if (isTigress && action.declaredAs() == null) {
            throw reject(RejectionReason.INVALID_TIGRESS_DECLARATION, "declaration missing");
        }
        if (!isTigress && action.declaredAs() != null) {
            throw reject(RejectionReason.INVALID_TIGRESS_DECLARATION,
                    "declaration on non-Tigress " + action.card());
        }
    }

    /**
     * follow 의무 (§6.2).
     *
     * <p>세 갈래다:
     * <ul>
     *   <li>리드 수트가 아직 확정되지 않았으면 (캐릭터 리드로 영구히 없거나, 탈출 리드로
     *       보류 중) 제약이 없다</li>
     *   <li><b>특수 카드는 언제나 낼 수 있다</b> — 원문: "리드된 카드가 색상 카드더라도,
     *       특수 카드는 언제든지 낼 수 있다"</li>
     *   <li>색상 카드를 낼 때는 리드 수트를 따라야 한다. 단 손에 그 색이 하나도 없으면
     *       아무 색이나 가능</li>
     * </ul>
     */
    public static boolean followsLeadSuit(TrickState trick, List<SkullCard> hand, SkullCard card) {
        Optional<SkullSuit> lead = trick.leadSuit();
        if (lead.isEmpty()) {
            return true;
        }
        if (card.isSpecial()) {
            return true;
        }
        if (card.suit() == lead.get()) {
            return true;
        }
        return !holdsSuit(hand, lead.get());
    }

    private static boolean holdsSuit(List<SkullCard> hand, SkullSuit suit) {
        return hand.stream().anyMatch(c -> c.isSuit() && c.suit() == suit);
    }

    // ---------- 상태 가드 ----------

    private static SkullKingState.Bidding requireBidding(SkullKingState state) {
        if (state instanceof SkullKingState.Bidding bidding) {
            return bidding;
        }
        throw reject(RejectionReason.NOT_IN_BIDDING_PHASE, state.phaseName());
    }

    private static SkullKingState.Playing requirePlaying(SkullKingState state) {
        if (state instanceof SkullKingState.Playing playing) {
            return playing;
        }
        throw reject(RejectionReason.NOT_IN_PLAYING_PHASE, state.phaseName());
    }

    private static void requireSeatInRange(SkullKingState state, int seat) {
        if (seat < 0 || seat >= state.seatCount()) {
            throw reject(RejectionReason.INVALID_STATE_FOR_ACTION,
                    "seat " + seat + " out of range for " + state.seatCount() + " seats");
        }
    }

    private static SkullKingActionRejectedException reject(RejectionReason reason, String detail) {
        return new SkullKingActionRejectedException(reason, detail);
    }
}
