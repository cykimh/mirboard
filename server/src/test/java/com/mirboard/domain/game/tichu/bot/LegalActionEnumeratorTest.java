package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
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
}
