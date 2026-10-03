package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.action.ActionValidator;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.hand.HandType;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LegalActionEnumeratorTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static PlayerState p(int seat, Card... cards) {
        return PlayerState.initial(seat, List.of(cards));
    }

    @Test
    void dealing_phase_offers_ready_for_not_ready_seat() {
        var dealing = new TichuState.Dealing(
                List.of(p(0, n(Suit.JADE, 2)), p(1), p(2), p(3)),
                8,
                Set.of(1, 2),  // 1, 2 만 ready
                Map.of());

        var legal = LegalActionEnumerator.enumerate(dealing, 0);

        assertThat(legal).hasSize(1).first()
                .isInstanceOf(TichuAction.Ready.class);
    }

    @Test
    void dealing_phase_already_ready_seat_has_no_actions() {
        var dealing = new TichuState.Dealing(
                List.of(p(0, n(Suit.JADE, 2)), p(1), p(2), p(3)),
                8,
                Set.of(0, 1, 2),  // 0 도 이미 ready
                Map.of());

        var legal = LegalActionEnumerator.enumerate(dealing, 0);

        assertThat(legal).isEmpty();
    }

    @Test
    void playing_phase_my_turn_includes_single_play_and_pass() {
        var players = List.of(
                p(0, n(Suit.JADE, 5), n(Suit.SWORD, 7)),
                p(1, n(Suit.JADE, 10)),
                p(2, n(Suit.STAR, 9)),
                p(3, n(Suit.PAGODA, 4)));
        // currentTurnSeat=0, lead=true (currentTop null → PassTrick 불법)
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        var legal = LegalActionEnumerator.enumerate(state, 0);

        // 5, 7 단일 플레이만 합법 (페어 없음). PassTrick 은 lead 이므로 reject.
        assertThat(legal).hasSize(2)
                .allMatch(a -> a instanceof TichuAction.PlayCard);
    }

    @Test
    void playing_phase_mahjong_offers_wish_variants_with_no_wish_first() {
        var players = List.of(
                p(0, Card.mahjong(), n(Suit.SWORD, 7)),
                p(1, n(Suit.JADE, 10)),
                p(2, n(Suit.STAR, 9)),
                p(3, n(Suit.PAGODA, 4)));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        var legal = LegalActionEnumerator.enumerate(state, 0);

        var mahjongPlays = legal.stream()
                .filter(a -> a instanceof TichuAction.PlayCard pc
                        && pc.cards().size() == 1
                        && pc.cards().get(0).is(Special.MAHJONG))
                .map(a -> (TichuAction.PlayCard) a)
                .toList();

        // 소원 없음 1 + 랭크 2~14 의 13 = 14종.
        assertThat(mahjongPlays).hasSize(14);
        assertThat(mahjongPlays.get(0).wishRank()).isNull();
        assertThat(mahjongPlays.stream().map(TichuAction.PlayCard::wishRank).filter(r -> r != null))
                .containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);

        // 마작이 아닌 카드(SWORD 7)는 변형을 만들지 않는다.
        var nonMahjongPlays = legal.stream()
                .filter(a -> a instanceof TichuAction.PlayCard pc
                        && pc.cards().size() == 1
                        && !pc.cards().get(0).is(Special.MAHJONG))
                .toList();
        assertThat(nonMahjongPlays).hasSize(1);
    }

    @Test
    void playing_phase_not_my_turn_no_actions() {
        var players = List.of(
                p(0, n(Suit.JADE, 5)),
                p(1, n(Suit.SWORD, 6)),
                p(2, n(Suit.STAR, 7)),
                p(3, n(Suit.PAGODA, 8)));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        var legal = LegalActionEnumerator.enumerate(state, 1);  // 1번 자리는 차례 아님

        assertThat(legal).isEmpty();
    }

    @Test
    void playing_phase_finished_player_no_actions() {
        var finished = new PlayerState(0, List.of(), null, 1, List.of());
        var players = List.of(finished, p(1, n(Suit.JADE, 5)), p(2), p(3));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        // 0번이 finished 라도 currentTurnSeat=0 이면 TurnManager 가 advance 되어야 하지만,
        // 일단 finished 인 좌석에서 enumerate 호출 시 빈 리스트 반환.
        var legal = LegalActionEnumerator.enumerate(state, 0);

        assertThat(legal).isEmpty();
    }

    @Test
    void round_end_no_actions() {
        var state = new TichuState.RoundEnd(
                List.of(p(0), p(1), p(2), p(3)), 50, 50);

        assertThat(LegalActionEnumerator.enumerate(state, 0)).isEmpty();
    }

    @Test
    void passing_phase_proposes_three_cards() {
        var hand = List.of(
                n(Suit.JADE, 2), n(Suit.JADE, 5), n(Suit.SWORD, 7),
                n(Suit.STAR, 9), n(Suit.PAGODA, 11), n(Suit.JADE, 13));
        var players = List.of(
                PlayerState.initial(0, hand),
                p(1), p(2), p(3));
        var passing = new TichuState.Passing(players, Map.of());

        var legal = LegalActionEnumerator.enumerate(passing, 0);

        assertThat(legal).hasSize(1).first()
                .isInstanceOf(TichuAction.PassCards.class);
    }

    @Test
    void passing_phase_already_submitted_no_actions() {
        var hand = List.of(n(Suit.JADE, 2), n(Suit.JADE, 5), n(Suit.SWORD, 7));
        var players = List.of(
                PlayerState.initial(0, hand),
                p(1), p(2), p(3));
        var passing = new TichuState.Passing(players,
                Map.of(0, new com.mirboard.domain.game.tichu.state.PassCardsSelection(
                        hand.get(0), hand.get(1), hand.get(2))));

        assertThat(LegalActionEnumerator.enumerate(passing, 0)).isEmpty();
    }

    // ---------- D-118 enumerateFull — 휴리스틱 봇 전용 후보 ----------

    private static List<HandType> playedTypes(List<TichuAction> legal) {
        return legal.stream()
                .filter(a -> a instanceof TichuAction.PlayCard)
                .map(a -> HandDetector.detect(((TichuAction.PlayCard) a).cards()).orElseThrow().type())
                .toList();
    }

    private static void assertAllValid(TichuState state, int seat, List<TichuAction> legal) {
        for (TichuAction a : legal) {
            ActionValidator.validate(state, seat, a);   // 던지면 실패
        }
    }

    @Test
    void enumerate_full_offers_declarations_in_dealing_windows() {
        var players = List.of(p(0, n(Suit.JADE, 2)), p(1), p(2), p(3));
        var dealing8 = new TichuState.Dealing(players, 8, Set.of(1), Map.of());
        var dealing14 = new TichuState.Dealing(players, 14, Set.of(1), Map.of());

        var full8 = LegalActionEnumerator.enumerateFull(dealing8, 0);
        var full14 = LegalActionEnumerator.enumerateFull(dealing14, 0);

        assertThat(full8).hasSize(2)
                .anyMatch(a -> a instanceof TichuAction.DeclareGrandTichu)
                .anyMatch(a -> a instanceof TichuAction.Ready);
        assertThat(full14).hasSize(2)
                .anyMatch(a -> a instanceof TichuAction.DeclareTichu)
                .anyMatch(a -> a instanceof TichuAction.Ready);
        assertAllValid(dealing8, 0, full8);
        assertAllValid(dealing14, 0, full14);
        // 기존 enumerate 계약은 그대로 — Ready 1개.
        assertThat(LegalActionEnumerator.enumerate(dealing8, 0)).singleElement()
                .isInstanceOf(TichuAction.Ready.class);
    }

    @Test
    void enumerate_full_offers_combos_and_tichu_on_fourteen_card_lead() {
        List<Card> hand = List.of(
                n(Suit.JADE, 2), n(Suit.SWORD, 3), n(Suit.STAR, 4), n(Suit.PAGODA, 5),
                n(Suit.JADE, 6),
                n(Suit.JADE, 9), n(Suit.SWORD, 9), n(Suit.STAR, 9),
                n(Suit.JADE, 11), n(Suit.SWORD, 11),
                n(Suit.JADE, 13), n(Suit.SWORD, 13), n(Suit.STAR, 13), n(Suit.PAGODA, 13));
        var players = List.of(PlayerState.initial(0, hand),
                p(1, n(Suit.STAR, 2)), p(2, n(Suit.STAR, 3)), p(3, n(Suit.STAR, 5)));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        var full = LegalActionEnumerator.enumerateFull(state, 0);

        assertThat(full).anyMatch(a -> a instanceof TichuAction.DeclareTichu);
        assertThat(playedTypes(full)).contains(
                HandType.SINGLE, HandType.PAIR, HandType.TRIPLE,
                HandType.STRAIGHT, HandType.FULL_HOUSE, HandType.BOMB);
        assertAllValid(state, 0, full);
        // enumerate 는 여전히 단일·페어·트리플만.
        assertThat(playedTypes(LegalActionEnumerator.enumerate(state, 0)))
                .doesNotContain(HandType.STRAIGHT, HandType.FULL_HOUSE, HandType.BOMB);
    }

    @Test
    void enumerate_full_follow_keeps_only_winning_same_shape_and_bombs() {
        List<Card> hand = List.of(
                n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.JADE, 10), n(Suit.SWORD, 10),
                n(Suit.JADE, 7), n(Suit.SWORD, 7), n(Suit.STAR, 7), n(Suit.PAGODA, 7));
        var top = HandDetector.detect(List.of(n(Suit.STAR, 8), n(Suit.PAGODA, 8))).orElseThrow();
        var trick = new TrickState(1, 0, top, 1, Set.of(), List.of(top),
                List.of(n(Suit.STAR, 8), n(Suit.PAGODA, 8)), null);
        var players = List.of(PlayerState.initial(0, hand),
                p(1, n(Suit.STAR, 2)), p(2, n(Suit.STAR, 3)), p(3, n(Suit.STAR, 5)));
        var state = new TichuState.Playing(players, trick, -1);

        var full = LegalActionEnumerator.enumerateFull(state, 0);

        assertAllValid(state, 0, full);
        assertThat(full).anyMatch(a -> a instanceof TichuAction.PassTrick);
        assertThat(playedTypes(full)).containsOnly(HandType.PAIR, HandType.BOMB);
        assertThat(playedTypes(full)).contains(HandType.BOMB);
    }

    @Test
    void enumerate_full_dragon_give_comes_first_even_for_finished_seat() {
        var dragon = HandDetector.detect(List.of(Card.dragon())).orElseThrow();
        var trick = new TrickState(2, 3, dragon, 2, Set.of(), List.of(dragon),
                List.of(Card.dragon()), null);
        var finished = new PlayerState(2, List.of(), TichuDeclaration.NONE, 1, List.of());
        var players = List.of(p(0, n(Suit.JADE, 5)), p(1, n(Suit.JADE, 6)), finished,
                p(3, n(Suit.JADE, 7)));
        var state = new TichuState.Playing(players, trick, 2);

        var full = LegalActionEnumerator.enumerateFull(state, 2);

        assertThat(full).containsExactlyInAnyOrder(
                new TichuAction.GiveDragonTrick(1), new TichuAction.GiveDragonTrick(3));
    }
}
