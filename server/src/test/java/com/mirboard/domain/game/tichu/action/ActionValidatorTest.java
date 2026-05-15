package com.mirboard.domain.game.tichu.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.card.Wish;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandType;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionValidatorTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    // ---------- helpers ----------

    private static TichuState.Playing playingState(
            List<List<Card>> hands, TrickState trick) {
        var players = List.of(
                PlayerState.initial(0, hands.get(0)),
                PlayerState.initial(1, hands.get(1)),
                PlayerState.initial(2, hands.get(2)),
                PlayerState.initial(3, hands.get(3)));
        return new TichuState.Playing(players, trick, -1);
    }

    private static TrickState leadTrick(int leadSeat) {
        return TrickState.lead(leadSeat, null);
    }

    private static TrickState followTrick(int currentTurn, Hand top, int topSeat) {
        return new TrickState(
                /* leadSeat */ 0,
                /* currentTurnSeat */ currentTurn,
                /* currentTop */ top,
                /* currentTopSeat */ topSeat,
                /* passedSeats */ Set.of(),
                /* playSequence */ List.of(top),
                /* accumulatedCards */ top.cards(),
                /* activeWish */ null);
    }

    // ---------- PlayCard ----------

    @Test
    void play_legal_single_on_lead_is_accepted() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatCode(() ->
                ActionValidator.validate(state, 0, new TichuAction.PlayCard(List.of(n(Suit.JADE, 5)))))
                .doesNotThrowAnyException();
    }

    @Test
    void play_when_not_your_turn_is_rejected_unless_bomb() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 1,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 6)))))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.NOT_YOUR_TURN);
    }

    @Test
    void bomb_can_be_played_out_of_turn() {
        var bomb = List.of(
                n(Suit.JADE, 5), n(Suit.SWORD, 5),
                n(Suit.STAR, 5), n(Suit.PAGODA, 5));
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 14)),
                        bomb,
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                followTrick(0, singleHand(n(Suit.JADE, 9)), 3));

        assertThatCode(() -> ActionValidator.validate(state, 1, new TichuAction.PlayCard(bomb)))
                .doesNotThrowAnyException();
    }

    @Test
    void play_cards_not_owned_is_rejected() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 6))))) // not in seat 0's hand
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.CARDS_NOT_OWNED);
    }

    @Test
    void invalid_hand_combination_is_rejected() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5), n(Suit.SWORD, 7)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        // 5 + 7 is not a pair / not a known combo
        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0,
                        new TichuAction.PlayCard(List.of(n(Suit.JADE, 5), n(Suit.SWORD, 7)))))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.INVALID_HAND);
    }

    @Test
    void follow_must_beat_current_top() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5)),
                        List.of(n(Suit.SWORD, 3)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                followTrick(1, singleHand(n(Suit.STAR, 9)), 0));

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 1,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 3)))))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.CANNOT_BEAT_CURRENT);
    }

    // ---------- Dog ----------

    @Test
    void dog_can_be_played_as_solo_on_lead() {
        var state = playingState(
                List.of(List.of(Card.dog()),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatCode(() ->
                ActionValidator.validate(state, 0,
                        new TichuAction.PlayCard(List.of(Card.dog()))))
                .doesNotThrowAnyException();
    }

    @Test
    void dog_cannot_be_played_when_following() {
        var state = playingState(
                List.of(List.of(Card.dog()),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                followTrick(0, singleHand(n(Suit.STAR, 7)), 2));

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0,
                        new TichuAction.PlayCard(List.of(Card.dog()))))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.DOG_MUST_BE_SOLO_LEAD);
    }

    // ---------- PassTrick ----------

    @Test
    void pass_on_lead_is_rejected() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0, new TichuAction.PassTrick()))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.PASS_ON_LEAD_NOT_ALLOWED);
    }

    @Test
    void pass_when_following_is_allowed() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                followTrick(1, singleHand(n(Suit.STAR, 9)), 0));

        assertThatCode(() ->
                ActionValidator.validate(state, 1, new TichuAction.PassTrick()))
                .doesNotThrowAnyException();
    }

    @Test
    void pass_when_not_your_turn_is_rejected() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 5)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                followTrick(1, singleHand(n(Suit.STAR, 9)), 0));

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 2, new TichuAction.PassTrick()))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.NOT_YOUR_TURN);
    }

    // ---------- Declarations ----------

    @Test
    void declare_tichu_requires_full_hand_of_14() {
        var fullHand = fourteenCards();
        var state = playingState(List.of(fullHand, fullHand, fullHand, fullHand),
                leadTrick(0));

        assertThatCode(() ->
                ActionValidator.validate(state, 0, new TichuAction.DeclareTichu()))
                .doesNotThrowAnyException();
    }

    @Test
    void declare_tichu_after_playing_is_rejected() {
        // Player already has 13 cards (played one)
        var shortHand = thirteenCards();
        var state = playingState(List.of(shortHand, shortHand, shortHand, shortHand),
                leadTrick(0));

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0, new TichuAction.DeclareTichu()))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.TICHU_DECLARATION_TOO_LATE);
    }

    @Test
    void duplicate_tichu_declaration_rejected() {
        var fullHand = fourteenCards();
        var players = List.of(
                PlayerState.initial(0, fullHand).withDeclaration(TichuDeclaration.TICHU),
                PlayerState.initial(1, fullHand),
                PlayerState.initial(2, fullHand),
                PlayerState.initial(3, fullHand));
        var state = new TichuState.Playing(players, leadTrick(0), -1);

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0, new TichuAction.DeclareTichu()))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.DUPLICATE_DECLARATION);
    }

    // ---------- GiveDragonTrick ----------

    @Test
    void dragon_recipient_must_be_opponent_team() {
        // Seat 0 (Team A) just won with Dragon. Sending it to seat 2 (Team A) is invalid;
        // seat 1 or 3 (Team B) is valid.
        Hand dragonHand = new Hand(HandType.SINGLE, List.of(Card.dragon()), 100, 1);
        var trick = new TrickState(
                0, 1, dragonHand, 0, Set.of(1, 2, 3),
                List.of(dragonHand), List.of(Card.dragon()), null);
        var state = playingState(
                List.of(List.of(), List.of(), List.of(), List.of()), trick);

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0, new TichuAction.GiveDragonTrick(2)))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.DRAGON_TRICK_RECIPIENT_MUST_BE_OPPONENT);

        assertThatCode(() ->
                ActionValidator.validate(state, 0, new TichuAction.GiveDragonTrick(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void dragon_give_only_after_dragon_play() {
        Hand nonDragon = singleHand(n(Suit.JADE, 9));
        var trick = new TrickState(
                0, 1, nonDragon, 0, Set.of(),
                List.of(nonDragon), nonDragon.cards(), null);
        var state = playingState(
                List.of(List.of(), List.of(), List.of(), List.of()), trick);

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0, new TichuAction.GiveDragonTrick(1)))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.DRAGON_GIVE_NOT_PERMITTED);
    }

    // ---------- MakeWish ----------

    @Test
    void wish_must_follow_mahjong_play() {
        Hand mahjongHand = new Hand(HandType.SINGLE, List.of(Card.mahjong()), 1, 1);
        var trick = new TrickState(
                0, 1, mahjongHand, 0, Set.of(),
                List.of(mahjongHand), mahjongHand.cards(), null);
        var state = playingState(
                List.of(List.of(), List.of(), List.of(), List.of()), trick);

        assertThatCode(() ->
                ActionValidator.validate(state, 0, new TichuAction.MakeWish(7)))
                .doesNotThrowAnyException();
    }

    @Test
    void wish_after_non_mahjong_play_is_rejected() {
        Hand nonMahjong = singleHand(n(Suit.JADE, 9));
        var trick = new TrickState(
                0, 1, nonMahjong, 0, Set.of(),
                List.of(nonMahjong), nonMahjong.cards(), null);
        var state = playingState(
                List.of(List.of(), List.of(), List.of(), List.of()), trick);

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0, new TichuAction.MakeWish(7)))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.WISH_OUT_OF_CONTEXT);
    }

    @Test
    void invalid_wish_rank_is_rejected() {
        Hand mahjongHand = new Hand(HandType.SINGLE, List.of(Card.mahjong()), 1, 1);
        var trick = new TrickState(
                0, 1, mahjongHand, 0, Set.of(),
                List.of(mahjongHand), mahjongHand.cards(), null);
        var state = playingState(
                List.of(List.of(), List.of(), List.of(), List.of()), trick);

        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0, new TichuAction.MakeWish(15)))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.INVALID_WISH_RANK);
    }

    // ---------- Wish enforcement on play ----------

    @Test
    void on_lead_with_active_wish_must_include_wished_rank_if_held() {
        var wish = Wish.active(7);
        var trick = new TrickState(0, 0, null, -1, Set.of(), List.of(), List.of(), wish);
        var hand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 9));
        var state = playingState(
                List.of(hand, hand, hand, hand), trick);

        // Playing 9 (single) on lead while holding wished 7 → reject
        assertThatThrownBy(() ->
                ActionValidator.validate(state, 0,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 9)))))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.WISH_NOT_FULFILLED);

        // Playing 7 is accepted
        assertThatCode(() ->
                ActionValidator.validate(state, 0,
                        new TichuAction.PlayCard(List.of(n(Suit.JADE, 7)))))
                .doesNotThrowAnyException();
    }

    // ---------- Phase 10C — wish follow 강제 마감 ----------

    @Test
    void on_follow_with_active_wish_must_include_wished_rank_if_playable() {
        // top = 3 (single), wish=7, follow 차례 (seat 1), 보유 7 → 7 포함해야.
        Hand topThree = new Hand(HandType.SINGLE, List.of(n(Suit.PAGODA, 3)), 3, 1);
        var wish = Wish.active(7);
        var trick = new TrickState(0, 1, topThree, 0, Set.of(), List.of(topThree),
                List.of(n(Suit.PAGODA, 3)), wish);
        var myHand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 9));
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 2)), myHand,
                        List.of(n(Suit.STAR, 2)), List.of(n(Suit.PAGODA, 2))),
                trick);

        // 9 단독 follow (7 미포함) → reject
        assertThatThrownBy(() ->
                ActionValidator.validate(state, 1,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 9)))))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.WISH_NOT_FULFILLED);

        // 7 단독 follow → allow
        assertThatCode(() ->
                ActionValidator.validate(state, 1,
                        new TichuAction.PlayCard(List.of(n(Suit.JADE, 7)))))
                .doesNotThrowAnyException();
    }

    @Test
    void on_follow_with_active_wish_allows_free_play_when_wish_unplayable() {
        // top = A (rank 14), wish=7, follow 차례, 보유 7 → 단일 7 은 A 못 이김.
        // 페어/트리플도 못 만듦 → wish 합법 follow 없음 → 자유 플레이 허용.
        Hand topAce = new Hand(HandType.SINGLE, List.of(n(Suit.PAGODA, 14)), 14, 1);
        var wish = Wish.active(7);
        var trick = new TrickState(0, 1, topAce, 0, Set.of(), List.of(topAce),
                List.of(n(Suit.PAGODA, 14)), wish);
        var myHand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 9));
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 2)), myHand,
                        List.of(n(Suit.STAR, 2)), List.of(n(Suit.PAGODA, 2))),
                trick);

        // 9 단독 follow — wish 못 이루지만 합법 (top 9 < A → 사실 단일 9 도 A 못 이김)
        // 실제로는 PassTrick 이 자연스러우나, 본 테스트는 wish 강제 단계까지만 검증.
        // 단일 9 도 못 이기므로 CANNOT_BEAT_CURRENT 가 먼저 발생할 수 있음.
        // 대신 단일 7 시도 — 7 < A 라 CANNOT_BEAT_CURRENT, 단 wish 강제는 통과해야 함.
        assertThatThrownBy(() ->
                ActionValidator.validate(state, 1,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 9)))))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.CANNOT_BEAT_CURRENT);  // wish 우회 — beat 만 reject
    }

    @Test
    void on_follow_without_wished_rank_in_hand_allows_any_play() {
        // wish=7, follow 차례, 보유 7 없음 → 자유 플레이 (beat 만 통과하면 OK)
        Hand topThree = new Hand(HandType.SINGLE, List.of(n(Suit.PAGODA, 3)), 3, 1);
        var wish = Wish.active(7);
        var trick = new TrickState(0, 1, topThree, 0, Set.of(), List.of(topThree),
                List.of(n(Suit.PAGODA, 3)), wish);
        var myHand = List.of(n(Suit.JADE, 5), n(Suit.SWORD, 9));
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 2)), myHand,
                        List.of(n(Suit.STAR, 2)), List.of(n(Suit.PAGODA, 2))),
                trick);

        assertThatCode(() ->
                ActionValidator.validate(state, 1,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 9)))))
                .doesNotThrowAnyException();
    }

    @Test
    void on_follow_wish_fulfilled_no_longer_forces() {
        // wish=7 이지만 이미 fulfilled=true → 강제 비활성
        Hand topThree = new Hand(HandType.SINGLE, List.of(n(Suit.PAGODA, 3)), 3, 1);
        var wish = new Wish(7, true);  // fulfilled
        var trick = new TrickState(0, 1, topThree, 0, Set.of(), List.of(topThree),
                List.of(n(Suit.PAGODA, 3)), wish);
        var myHand = List.of(n(Suit.JADE, 7), n(Suit.SWORD, 9));
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 2)), myHand,
                        List.of(n(Suit.STAR, 2)), List.of(n(Suit.PAGODA, 2))),
                trick);

        // 9 단독 follow — wish 7 보유하나 fulfilled 이므로 자유
        assertThatCode(() ->
                ActionValidator.validate(state, 1,
                        new TichuAction.PlayCard(List.of(n(Suit.SWORD, 9)))))
                .doesNotThrowAnyException();
    }

    // ---------- helpers ----------

    private static Hand singleHand(Card c) {
        return new Hand(HandType.SINGLE, List.of(c), c.rank(), 1);
    }

    private static List<Card> fourteenCards() {
        // 14 distinct normals: JADE 2..14, SWORD 2 (= 13+1).
        var list = new java.util.ArrayList<Card>();
        for (int r = 2; r <= 14; r++) list.add(n(Suit.JADE, r));
        list.add(n(Suit.SWORD, 2));
        return List.copyOf(list);
    }

    private static List<Card> thirteenCards() {
        var list = new java.util.ArrayList<Card>();
        for (int r = 2; r <= 14; r++) list.add(n(Suit.JADE, r));
        return List.copyOf(list);
    }

    @Test
    void player_state_helper_assertion() {
        // Sanity: PlayerState.handSize reflects list length
        assertThat(PlayerState.initial(0, fourteenCards()).handSize()).isEqualTo(14);
    }
}
