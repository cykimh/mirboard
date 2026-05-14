package com.mirboard.domain.game.tichu.action;

import com.mirboard.domain.game.tichu.TurnManager;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandComparator;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 액션을 상태에 적용하기 전에 합법성을 검증한다. 실패 시 {@link TichuActionRejectedException}.
 * 본 클래스는 상태를 변경하지 않는다.
 */
public final class ActionValidator {

    private ActionValidator() {
    }

    public static void validate(TichuState state, int seat, TichuAction action) {
        switch (action) {
            case TichuAction.PlayCard play -> validatePlayCard(state, seat, play);
            case TichuAction.PassTrick __ -> validatePassTrick(state, seat);
            case TichuAction.DeclareTichu __ -> validateDeclareTichu(state, seat);
            case TichuAction.DeclareGrandTichu __ -> validateDeclareGrandTichu(state, seat);
            case TichuAction.PassCards pc -> validatePassCards(state, seat, pc);
            case TichuAction.MakeWish w -> validateMakeWish(state, seat, w);
            case TichuAction.GiveDragonTrick g -> validateGiveDragonTrick(state, seat, g);
        }
    }

    // ---------- PlayCard ----------
    private static void validatePlayCard(TichuState state, int seat, TichuAction.PlayCard action) {
        TichuState.Playing playing = requirePlaying(state);
        TrickState trick = playing.trick();
        PlayerState player = playing.players().get(seat);

        if (action.cards().isEmpty()) {
            throw reject(RejectionReason.EMPTY_CARDS);
        }
        if (!playerOwnsAll(player.hand(), action.cards())) {
            throw reject(RejectionReason.CARDS_NOT_OWNED);
        }

        Hand detected = HandDetector.detect(action.cards())
                .orElseThrow(() -> reject(RejectionReason.INVALID_HAND));

        // Turn check: only bombs may interrupt out of turn.
        if (trick.currentTurnSeat() != seat && !detected.isBomb()) {
            throw reject(RejectionReason.NOT_YOUR_TURN);
        }

        // Dog rule: solo + lead only.
        boolean isDog = action.cards().size() == 1 && action.cards().get(0).is(Special.DOG);
        if (isDog && !trick.isLead()) {
            throw reject(RejectionReason.DOG_MUST_BE_SOLO_LEAD);
        }

        // Follow: must beat current.
        if (!trick.isLead() && !HandComparator.canBeat(detected, trick.currentTop())) {
            throw reject(RejectionReason.CANNOT_BEAT_CURRENT);
        }

        // Active wish enforcement (conservative): if player holds wished rank and
        // their play doesn't include it, reject. Phase 3f will refine with full
        // "legal play exists" search.
        if (trick.hasActiveWish()) {
            int wishedRank = trick.activeWish().rank();
            boolean hasWishedInHand = player.hand().stream()
                    .anyMatch(c -> c.isNormal() && c.rank() == wishedRank);
            boolean playsWished = action.cards().stream()
                    .anyMatch(c -> c.isNormal() && c.rank() == wishedRank);
            if (hasWishedInHand && !playsWished && trick.isLead()) {
                // Strict only on lead; on follow, deferred (need beat check).
                throw reject(RejectionReason.WISH_NOT_FULFILLED);
            }
        }
    }

    // ---------- PassTrick ----------
    private static void validatePassTrick(TichuState state, int seat) {
        TichuState.Playing playing = requirePlaying(state);
        TrickState trick = playing.trick();
        if (trick.currentTurnSeat() != seat) {
            throw reject(RejectionReason.NOT_YOUR_TURN);
        }
        if (trick.isLead()) {
            throw reject(RejectionReason.PASS_ON_LEAD_NOT_ALLOWED);
        }
    }

    // ---------- Declarations ----------
    private static void validateDeclareTichu(TichuState state, int seat) {
        if (!(state instanceof TichuState.Playing playing)) {
            throw reject(RejectionReason.INVALID_STATE_FOR_ACTION);
        }
        PlayerState p = playing.players().get(seat);
        if (p.declaration() != TichuDeclaration.NONE) {
            throw reject(RejectionReason.DUPLICATE_DECLARATION);
        }
        if (p.handSize() != 14) {
            throw reject(RejectionReason.TICHU_DECLARATION_TOO_LATE);
        }
    }

    private static void validateDeclareGrandTichu(TichuState state, int seat) {
        // Grand Tichu must happen in the 8-card dealing phase. Phase 3d does not
        // model Dealing8 yet, so accept only when player has 8 cards regardless
        // of state subtype. The engine in Phase 3f will tighten this.
        List<PlayerState> players = state.players();
        PlayerState p = players.get(seat);
        if (p.declaration() != TichuDeclaration.NONE) {
            throw reject(RejectionReason.DUPLICATE_DECLARATION);
        }
        if (p.handSize() != 8) {
            throw reject(RejectionReason.GRAND_TICHU_DECLARATION_WRONG_PHASE);
        }
    }

    // ---------- PassCards ----------
    private static void validatePassCards(TichuState state, int seat, TichuAction.PassCards pc) {
        if (!(state instanceof TichuState.Passing passing)) {
            throw reject(RejectionReason.INVALID_STATE_FOR_ACTION);
        }
        PlayerState player = passing.players().get(seat);
        List<Card> chosen = List.of(pc.toLeft(), pc.toPartner(), pc.toRight());
        // 3장 모두 다른 카드여야 하고 모두 본인 손에 있어야 함.
        if (new HashSet<>(chosen).size() != 3) {
            throw reject(RejectionReason.CARDS_NOT_OWNED, "passed cards must be distinct");
        }
        if (!playerOwnsAll(player.hand(), chosen)) {
            throw reject(RejectionReason.CARDS_NOT_OWNED);
        }
        if (Boolean.TRUE.equals(passing.submitted().get(seat))) {
            throw reject(RejectionReason.DUPLICATE_DECLARATION, "pass already submitted");
        }
    }

    // ---------- MakeWish ----------
    private static void validateMakeWish(TichuState state, int seat, TichuAction.MakeWish action) {
        TichuState.Playing playing = requirePlaying(state);
        if (action.rank() < 2 || action.rank() > 14) {
            throw reject(RejectionReason.INVALID_WISH_RANK);
        }
        TrickState trick = playing.trick();
        // 소원은 Mahjong 을 막 낸 직후에만 가능. 가장 마지막 플레이가 Mahjong 인지 확인.
        if (trick.currentTop() == null) {
            throw reject(RejectionReason.WISH_OUT_OF_CONTEXT);
        }
        List<Card> top = trick.currentTop().cards();
        boolean topIsMahjong = top.size() == 1 && top.get(0).is(Special.MAHJONG);
        if (!topIsMahjong || trick.currentTopSeat() != seat) {
            throw reject(RejectionReason.WISH_OUT_OF_CONTEXT);
        }
        if (trick.activeWish() != null) {
            throw reject(RejectionReason.DUPLICATE_DECLARATION, "wish already made this round");
        }
    }

    // ---------- GiveDragonTrick ----------
    private static void validateGiveDragonTrick(TichuState state, int seat, TichuAction.GiveDragonTrick action) {
        TichuState.Playing playing = requirePlaying(state);
        TrickState trick = playing.trick();
        if (trick.currentTop() == null) {
            throw reject(RejectionReason.NO_DRAGON_TRICK_TO_GIVE);
        }
        List<Card> top = trick.currentTop().cards();
        boolean topIsDragon = top.size() == 1 && top.get(0).is(Special.DRAGON);
        if (!topIsDragon || trick.currentTopSeat() != seat) {
            throw reject(RejectionReason.DRAGON_GIVE_NOT_PERMITTED);
        }
        if (action.toSeat() < 0 || action.toSeat() >= TurnManager.SEATS) {
            throw reject(RejectionReason.DRAGON_TRICK_RECIPIENT_MUST_BE_OPPONENT);
        }
        if (Team.ofSeat(seat) == Team.ofSeat(action.toSeat())) {
            throw reject(RejectionReason.DRAGON_TRICK_RECIPIENT_MUST_BE_OPPONENT);
        }
    }

    // ---------- helpers ----------
    private static TichuState.Playing requirePlaying(TichuState state) {
        if (!(state instanceof TichuState.Playing playing)) {
            throw reject(RejectionReason.NOT_IN_PLAYING_PHASE);
        }
        return playing;
    }

    private static boolean playerOwnsAll(List<Card> hand, List<Card> required) {
        Map<Card, Integer> handCounts = new HashMap<>();
        for (Card c : hand) {
            handCounts.merge(c, 1, Integer::sum);
        }
        for (Card c : required) {
            Integer remaining = handCounts.get(c);
            if (remaining == null || remaining == 0) return false;
            if (remaining == 1) handCounts.remove(c);
            else handCounts.put(c, remaining - 1);
        }
        return true;
    }

    private static TichuActionRejectedException reject(RejectionReason reason) {
        return new TichuActionRejectedException(reason);
    }

    private static TichuActionRejectedException reject(RejectionReason reason, String detail) {
        return new TichuActionRejectedException(reason, detail);
    }
}
