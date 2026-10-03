package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.TichuEngine;
import com.mirboard.domain.game.tichu.action.ActionValidator;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.card.Wish;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * D-118 — {@link HeuristicBotPolicy} 결정 지점별 손설계 시나리오. 좌석 0 이 봇(파트너 2,
 * 상대 1·3)이다. 상대·파트너 손패는 장수만 의미가 있어 남는 카드로 채운다.
 */
class HeuristicBotPolicyTest {

    private static final GameContext CTX = new GameContext("bot-test", List.of(1L, 2L, 3L, 4L));
    private static final HeuristicBotPolicy POLICY =
            new HeuristicBotPolicy(HeuristicBotPolicy.Tuning.DEFAULT);

    // ---------- 조립 도우미 ----------

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static final Suit J = Suit.JADE;
    private static final Suit S = Suit.SWORD;
    private static final Suit T = Suit.STAR;
    private static final Suit P = Suit.PAGODA;

    /** 좌석별 손패 구성기. 지정하지 않은 좌석은 sizes 만큼 남는 카드로 채운다. */
    private static final class Table {
        final List<List<Card>> hands = new ArrayList<>(List.of(
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        final TichuDeclaration[] decl = {TichuDeclaration.NONE, TichuDeclaration.NONE,
                TichuDeclaration.NONE, TichuDeclaration.NONE};
        final int[] finished = {-1, -1, -1, -1};
        final List<Card> played = new ArrayList<>();

        Table me(Card... cards) {
            hands.get(0).addAll(List.of(cards));
            return this;
        }

        Table seat(int seat, Card... cards) {
            hands.get(seat).addAll(List.of(cards));
            return this;
        }

        /** seat 의 손패를 count 장이 되도록 남는 카드로 채운다 (일반 카드 높은 것부터, 모자라면 특수). */
        Table size(int seat, int count) {
            List<Card> pool = new ArrayList<>(Deck.unshuffled().cards());
            hands.forEach(pool::removeAll);
            pool.removeAll(played);
            pool.sort((a, b) -> Boolean.compare(b.isSpecial(), a.isSpecial()));
            List<Card> h = hands.get(seat);
            while (h.size() < count) h.add(pool.remove(pool.size() - 1));
            return this;
        }

        Table declared(int seat, TichuDeclaration d) {
            decl[seat] = d;
            return this;
        }

        Table finishedAs(int seat, int order) {
            finished[seat] = order;
            hands.get(seat).clear();
            return this;
        }

        Table played(Card... cards) {
            played.addAll(List.of(cards));
            return this;
        }

        List<PlayerState> players() {
            List<PlayerState> out = new ArrayList<>();
            for (int s = 0; s < 4; s++) {
                List<Card> won = s == 1 ? played : List.of();
                out.add(new PlayerState(s, hands.get(s), decl[s], finished[s], won));
            }
            return out;
        }

        int firstFinisher() {
            for (int s = 0; s < 4; s++) if (finished[s] == 1) return s;
            return -1;
        }

        TichuState.Playing lead() {
            return lead(null);
        }

        TichuState.Playing lead(Wish wish) {
            return new TichuState.Playing(players(), TrickState.lead(0, wish), firstFinisher());
        }

        /** topSeat 이 top 을 냈고 지금 좌석 0 차례. */
        TichuState.Playing follow(int topSeat, List<Card> top, Set<Integer> passed, Wish wish) {
            Hand hand = HandDetector.detect(top).orElseThrow();
            var trick = new TrickState(topSeat, 0, hand, topSeat, passed, List.of(hand), top, wish);
            return new TichuState.Playing(players(), trick, firstFinisher());
        }

        TichuState.Playing follow(int topSeat, Card... top) {
            return follow(topSeat, List.of(top), Set.of(), null);
        }
    }

    private static TichuAction choose(TichuState state) {
        var decision = POLICY.decide(state, 0);
        assertThat(decision.fellBack()).as("검증 폴백 없이 결정해야 한다").isFalse();
        if (decision.action() != null) ActionValidator.validate(state, 0, decision.action());
        return decision.action();
    }

    private static TichuAction.PlayCard play(Card... cards) {
        return new TichuAction.PlayCard(List.of(cards));
    }

    private static final TichuAction PASS = new TichuAction.PassTrick();

    // ---------- 선언 ----------

    @Nested
    class Declarations {

        private TichuState.Dealing dealing8(List<Card> mine, TichuDeclaration partner) {
            var t = new Table().me(mine.toArray(Card[]::new)).size(1, 8).size(2, 8).size(3, 8)
                    .declared(2, partner);
            return new TichuState.Dealing(t.players(), 8, Set.of(), Map.of());
        }

        @Test
        void strong_eight_declares_grand_tichu() {
            var state = dealing8(List.of(Card.dragon(), Card.phoenix(), n(J, 14), n(S, 14),
                    n(T, 9), n(P, 7), n(J, 5), n(S, 3)), TichuDeclaration.NONE);

            assertThat(choose(state)).isInstanceOf(TichuAction.DeclareGrandTichu.class);
        }

        @Test
        void weak_eight_just_readies() {
            var state = dealing8(List.of(n(J, 2), n(S, 3), n(T, 5), n(P, 7), n(J, 8),
                    n(S, 9), n(T, 11), n(P, 12)), TichuDeclaration.NONE);

            assertThat(choose(state)).isInstanceOf(TichuAction.Ready.class);
        }

        @Test
        void strong_eight_stays_quiet_when_partner_already_declared() {
            var state = dealing8(List.of(Card.dragon(), Card.phoenix(), n(J, 14), n(S, 14),
                    n(T, 9), n(P, 7), n(J, 5), n(S, 3)), TichuDeclaration.GRAND_TICHU);

            assertThat(choose(state)).isInstanceOf(TichuAction.Ready.class);
        }

        private Table strongFourteen() {
            return new Table().me(Card.dragon(), Card.phoenix(), n(J, 14), n(J, 13), n(S, 13),
                    n(J, 12), n(S, 12), n(T, 12), Card.mahjong(), n(J, 2), n(S, 3), n(T, 4),
                    n(P, 5), n(J, 6));
        }

        @Test
        void strong_fourteen_declares_tichu_on_first_turn_then_plays() {
            var state = strongFourteen().size(1, 14).size(2, 14).size(3, 14).lead();

            assertThat(choose(state)).isInstanceOf(TichuAction.DeclareTichu.class);

            TichuState after = new TichuEngine(CTX)
                    .apply(state, 0, new TichuAction.DeclareTichu()).newState();
            assertThat(choose(after)).isInstanceOf(TichuAction.PlayCard.class);
        }

        @Test
        void no_tichu_when_an_opponent_is_already_down_to_nine() {
            var state = strongFourteen().size(1, 9).size(2, 14).size(3, 14).lead();

            assertThat(choose(state)).isInstanceOf(TichuAction.PlayCard.class);
        }

        /*
         * 패스 후 선언의 비컨트롤 묶음 문턱(보정 3차): 비컨트롤 묶음이 컨트롤 수보다 적어야 한다.
         * 아래 두 손은 controls 3 이고 나머지 문턱(losers·상대 장수)은 모두 통과한다.
         */

        @Test
        void no_tichu_with_three_controls_and_three_open_groups() {
            // 용 · A · KK | 2-7 스트레이트 · 99 · JJ — controls 3, 비컨트롤 3, losers 1
            var state = new Table().me(Card.dragon(), n(J, 14), n(J, 13), n(S, 13), n(J, 2),
                    n(S, 3), n(T, 4), n(P, 5), n(J, 6), n(S, 7), n(J, 9), n(S, 9), n(T, 11),
                    n(P, 11)).size(1, 14).size(2, 14).size(3, 14).lead();

            assertThat(choose(state)).isInstanceOf(TichuAction.PlayCard.class);
        }

        @Test
        void tichu_with_three_controls_and_two_open_groups() {
            // 용 · A · KKK | 2-9 스트레이트 · J — controls 3, 비컨트롤 2, losers 0
            var state = new Table().me(Card.dragon(), n(J, 14), n(J, 13), n(S, 13), n(T, 13),
                    n(J, 2), n(S, 3), n(T, 4), n(P, 5), n(J, 6), n(S, 7), n(T, 8), n(P, 9),
                    n(T, 11)).size(1, 14).size(2, 14).size(3, 14).lead();

            assertThat(choose(state)).isInstanceOf(TichuAction.DeclareTichu.class);
        }
    }

    // ---------- 패스 ----------

    @Nested
    class Passing {

        /** 컨트롤 2(용·A), 비컨트롤 단일 K·Q·5·3, 페어 2·4·7·9. */
        private final List<Card> ordinary = List.of(Card.dragon(), n(J, 14), n(S, 13),
                n(J, 2), n(S, 2), n(T, 4), n(P, 4), n(J, 7), n(S, 7), n(T, 9), n(P, 9),
                n(J, 12), n(S, 5), n(P, 3));

        private TichuState.Passing passing(List<Card> mine, TichuDeclaration partner) {
            var t = new Table().me(mine.toArray(Card[]::new)).size(1, 14).size(2, 14).size(3, 14)
                    .declared(2, partner);
            return new TichuState.Passing(t.players(), Map.of());
        }

        @Test
        void ordinary_hand_gives_partner_the_best_non_control_single_and_cheapest_two_away() {
            var action = (TichuAction.PassCards) choose(passing(ordinary, TichuDeclaration.NONE));

            assertThat(action.toPartner()).isEqualTo(n(S, 13));
            assertThat(List.of(action.toLeft(), action.toRight()))
                    .containsExactlyInAnyOrder(n(P, 3), n(S, 5));
            // 더 약한 카드가 왼쪽(s+1).
            assertThat(action.toLeft()).isEqualTo(n(P, 3));
        }

        @Test
        void declared_partner_receives_the_strongest_card() {
            var action = (TichuAction.PassCards) choose(passing(ordinary, TichuDeclaration.TICHU));

            assertThat(action.toPartner()).isEqualTo(Card.dragon());
        }

        @Test
        void never_gives_dragon_phoenix_or_bomb_cards_to_opponents() {
            var mine = List.of(Card.dragon(), Card.phoenix(), n(J, 8), n(S, 8), n(T, 8), n(P, 8),
                    n(J, 2), n(S, 3), n(T, 5), n(P, 6), n(J, 10), n(S, 11), n(T, 13), n(P, 14));
            var action = (TichuAction.PassCards) choose(passing(mine, TichuDeclaration.NONE));

            assertThat(List.of(action.toLeft(), action.toRight())).doesNotContain(
                    Card.dragon(), Card.phoenix(), n(J, 8), n(S, 8), n(T, 8), n(P, 8));
            assertThat(Set.of(action.toLeft(), action.toPartner(), action.toRight())).hasSize(3);
        }
    }

    // ---------- 리드 ----------

    @Nested
    class Lead {

        @Test
        void single_combo_hand_goes_out_at_once() {
            var state = new Table().me(n(J, 3), n(S, 4), n(T, 5), n(P, 6), n(J, 7))
                    .size(1, 8).size(2, 8).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(
                    play(n(J, 3), n(S, 4), n(T, 5), n(P, 6), n(J, 7)));
        }

        @Test
        void leads_the_low_straight_before_a_king() {
            var state = new Table().me(n(J, 2), n(S, 3), n(T, 4), n(P, 5), n(J, 6), n(S, 13))
                    .size(1, 8).size(2, 8).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(
                    play(n(J, 2), n(S, 3), n(T, 4), n(P, 5), n(J, 6)));
        }

        @Test
        void cashes_the_unbeatable_dragon_first_with_two_groups_left() {
            var state = new Table().me(Card.dragon(), n(J, 3))
                    .size(1, 8).size(2, 8).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(play(Card.dragon()));
        }

        @Test
        void leads_a_pair_instead_of_a_single_when_an_opponent_has_one_card() {
            var state = new Table().me(n(J, 3), n(J, 9), n(S, 9), n(T, 13))
                    .size(1, 1).size(2, 8).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(play(n(J, 9), n(S, 9)));
        }

        @Test
        void mahjong_lead_wishes_for_the_highest_rank_not_in_hand() {
            var state = new Table().me(Card.mahjong(), n(J, 14), n(S, 13))
                    .size(1, 8).size(2, 8).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(
                    new TichuAction.PlayCard(List.of(Card.mahjong()), 12));
        }
    }

    // ---------- 팔로우 · 폭탄 ----------

    @Nested
    class Follow {

        @Test
        void beats_a_seven_with_the_nine_not_the_ace() {
            var state = new Table().me(n(S, 9), n(J, 14), n(P, 2))
                    .size(1, 8).size(2, 8).size(3, 8).follow(3, n(T, 7));

            assertThat(choose(state)).isEqualTo(play(n(S, 9)));
        }

        @Test
        void passes_when_partner_is_on_top() {
            var state = new Table().me(n(S, 9), n(J, 14), n(P, 2))
                    .size(1, 8).size(2, 8).size(3, 8).follow(2, n(T, 7));

            assertThat(choose(state)).isEqualTo(PASS);
        }

        @Test
        void does_not_break_a_pair_for_a_pointless_trick() {
            var state = new Table().me(n(J, 9), n(S, 9), n(P, 3))
                    .size(1, 8).size(2, 8).size(3, 8).follow(3, n(T, 7));

            assertThat(choose(state)).isEqualTo(PASS);
        }

        @Test
        void keeps_the_dragon_on_a_pointless_trick_but_spends_it_on_a_declarer() {
            var quiet = new Table().me(Card.dragon(), n(J, 3), n(S, 6), n(T, 9))
                    .size(1, 8).size(2, 8).size(3, 8).follow(3, n(P, 14));
            assertThat(choose(quiet)).isEqualTo(PASS);

            var declarer = new Table().me(Card.dragon(), n(J, 3), n(S, 6), n(T, 9))
                    .size(1, 8).size(2, 8).size(3, 8).declared(3, TichuDeclaration.TICHU)
                    .follow(3, n(P, 14));
            assertThat(choose(declarer)).isEqualTo(play(Card.dragon()));
        }

        private Table bombs() {
            return new Table().me(n(J, 5), n(S, 5), n(T, 5), n(P, 5),
                    n(J, 9), n(S, 9), n(T, 9), n(P, 9), n(J, 3), n(S, 4));
        }

        @Test
        void bombs_with_the_weakest_bomb_when_an_opponent_on_top_is_almost_out() {
            var state = bombs().size(1, 8).size(2, 8).size(3, 2).follow(3, n(P, 13));

            // 정규 순서: rank → 무늬 순(JADE, SWORD, PAGODA, STAR).
            assertThat(choose(state)).isEqualTo(play(n(J, 5), n(S, 5), n(P, 5), n(T, 5)));
        }

        @Test
        void never_bombs_the_partner() {
            var state = bombs().size(1, 8).size(2, 2).size(3, 8).follow(2, n(P, 13));

            assertThat(choose(state)).isEqualTo(PASS);
        }

        @Test
        void passes_instead_of_bombing_a_pointless_unthreatening_trick() {
            var state = bombs().size(1, 8).size(2, 8).size(3, 8).follow(3, n(P, 12));

            assertThat(choose(state)).isEqualTo(PASS);
        }

        @Test
        void plays_the_wished_rank_instead_of_passing() {
            var state = new Table().me(n(J, 8), n(S, 8), n(P, 3))
                    .size(1, 8).size(2, 8).size(3, 8)
                    .follow(3, List.of(n(T, 5)), Set.of(), Wish.active(8));

            var action = choose(state);
            assertThat(action).isInstanceOf(TichuAction.PlayCard.class);
            assertThat(((TichuAction.PlayCard) action).cards())
                    .allMatch(c -> c.rank() == 8);
        }

        @Test
        void blocks_with_the_unbeatable_dragon_when_the_next_opponent_is_on_one_card() {
            // 파트너(2)가 9 를 냈고 3 은 패스, 다음 응수자 1 이 1장.
            var state = new Table().me(Card.dragon(), n(J, 4), n(S, 6), n(T, 10))
                    .size(1, 1).size(2, 8).size(3, 8)
                    .follow(2, List.of(n(P, 9)), Set.of(3), null);

            assertThat(choose(state)).isEqualTo(play(Card.dragon()));
        }
    }

    // ---------- 파트너 선언 가드 ----------

    @Nested
    class PartnerGuard {

        private Table guarded() {
            return new Table().declared(2, TichuDeclaration.TICHU);
        }

        @Test
        void does_not_go_out_over_a_declared_partner_on_top() {
            var state = guarded().me(n(J, 10)).size(1, 8).size(2, 5).size(3, 8)
                    .follow(2, n(P, 9));

            assertThat(choose(state)).isEqualTo(PASS);
        }

        @Test
        void leads_the_lowest_single_instead_of_going_out() {
            var state = guarded().me(n(J, 5), n(S, 6), n(T, 7), n(P, 8), n(J, 9))
                    .size(1, 8).size(2, 5).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(play(n(J, 5)));
        }

        @Test
        void goes_out_when_an_opponent_threatens() {
            var state = guarded().me(n(J, 5), n(S, 6), n(T, 7), n(P, 8), n(J, 9))
                    .size(1, 1).size(2, 5).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(
                    play(n(J, 5), n(S, 6), n(T, 7), n(P, 8), n(J, 9)));
        }

        @Test
        void goes_out_when_i_declared_too() {
            var state = guarded().declared(0, TichuDeclaration.TICHU)
                    .me(n(J, 5), n(S, 6), n(T, 7), n(P, 8), n(J, 9))
                    .size(1, 8).size(2, 5).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(
                    play(n(J, 5), n(S, 6), n(T, 7), n(P, 8), n(J, 9)));
        }

        @Test
        void goes_out_when_every_lead_would() {
            var state = guarded().me(n(J, 7)).size(1, 8).size(2, 5).size(3, 8).lead();

            assertThat(choose(state)).isEqualTo(play(n(J, 7)));
        }
    }

    // ---------- 소원 강제 하 규칙 건너뛰기 ----------

    @Nested
    class WishForcing {

        @Test
        void wish_beats_the_dog_rule() {
            var state = new Table().me(Card.dog(), n(J, 8), n(S, 3), n(T, 13))
                    .size(1, 8).size(2, 2).size(3, 8)
                    .declared(2, TichuDeclaration.TICHU)
                    .lead(Wish.active(8));

            assertThat(choose(state)).isEqualTo(play(n(J, 8)));
        }

        @Test
        void wish_beats_the_partner_on_one_card_rule() {
            var state = new Table().me(n(J, 8), n(S, 3), n(T, 13))
                    .size(1, 8).size(2, 1).size(3, 8)
                    .lead(Wish.active(8));

            assertThat(choose(state)).isEqualTo(play(n(J, 8)));
        }
    }

    // ---------- 용 양도 · 결정성 ----------

    @Nested
    class DragonGiveAndDeterminism {

        private TichuState.Playing dragonPending(Table t) {
            Hand dragon = HandDetector.detect(List.of(Card.dragon())).orElseThrow();
            var trick = new TrickState(0, 1, dragon, 0, Set.of(), List.of(dragon),
                    List.of(Card.dragon()), null);
            return new TichuState.Playing(t.players(), trick, t.firstFinisher());
        }

        @Test
        void gives_the_dragon_trick_to_the_opponent_with_more_cards() {
            var state = dragonPending(new Table().me(n(J, 4)).size(1, 3).size(2, 8).size(3, 7));

            assertThat(choose(state)).isEqualTo(new TichuAction.GiveDragonTrick(3));
        }

        @Test
        void gives_to_the_remaining_opponent_when_one_has_finished() {
            var state = dragonPending(new Table().me(n(J, 4)).size(1, 3).size(2, 8)
                    .finishedAs(3, 1));

            assertThat(choose(state)).isEqualTo(new TichuAction.GiveDragonTrick(1));
        }

        @Test
        void a_seat_that_went_out_on_the_dragon_still_gives_it() {
            var state = dragonPending(new Table().finishedAs(0, 1)
                    .size(1, 3).size(2, 8).size(3, 7));

            assertThat(HeuristicBotPolicy.choose(state, 0))
                    .isEqualTo(new TichuAction.GiveDragonTrick(3));
        }

        @Test
        void same_state_same_action() {
            var state = new Table().me(n(J, 3), n(S, 3), n(T, 7), n(P, 9), n(J, 11), n(S, 14))
                    .size(1, 8).size(2, 8).size(3, 8).lead();

            assertThat(HeuristicBotPolicy.choose(state, 0))
                    .isEqualTo(HeuristicBotPolicy.choose(state, 0));
        }
    }
}
