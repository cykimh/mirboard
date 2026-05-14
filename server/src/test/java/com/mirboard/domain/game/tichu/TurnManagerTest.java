package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandType;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TurnManagerTest {

    @Test
    void seats_wrap_clockwise() {
        assertThat(TurnManager.nextSeat(0)).isEqualTo(1);
        assertThat(TurnManager.nextSeat(3)).isEqualTo(0);
    }

    @Test
    void partner_is_opposite_seat() {
        assertThat(TurnManager.partnerOf(0)).isEqualTo(2);
        assertThat(TurnManager.partnerOf(1)).isEqualTo(3);
    }

    @Test
    void team_assignment_alternates() {
        assertThat(Team.ofSeat(0)).isEqualTo(Team.A);
        assertThat(Team.ofSeat(1)).isEqualTo(Team.B);
        assertThat(Team.ofSeat(2)).isEqualTo(Team.A);
        assertThat(Team.ofSeat(3)).isEqualTo(Team.B);
    }

    @Test
    void advance_turn_skips_passed_players() {
        var hand = new Hand(HandType.SINGLE, List.of(Card.normal(Suit.JADE, 5)), 5, 1);
        var trick = new TrickState(
                /* leadSeat */ 0,
                /* currentTurnSeat */ 1,
                /* currentTop */ hand,
                /* currentTopSeat */ 0,
                /* passedSeats */ Set.of(2),
                /* playSequence */ List.of(hand),
                /* accumulatedCards */ List.of(),
                /* activeWish */ null);
        var players = List.of(playerAt(0), playerAt(1), playerAt(2), playerAt(3));
        var state = new TichuState.Playing(players, trick, -1);

        // Currently seat 1's turn. After advancing: 2 is passed → skip → 3.
        assertThat(TurnManager.advanceTurn(state)).isEqualTo(3);
    }

    @Test
    void advance_turn_returns_to_top_seat_when_all_others_passed() {
        var hand = new Hand(HandType.SINGLE, List.of(Card.normal(Suit.JADE, 5)), 5, 1);
        var trick = new TrickState(
                0, 3, hand, 0, Set.of(1, 2, 3), List.of(hand), List.of(), null);
        var players = List.of(playerAt(0), playerAt(1), playerAt(2), playerAt(3));
        var state = new TichuState.Playing(players, trick, -1);

        assertThat(TurnManager.advanceTurn(state)).isEqualTo(0);
    }

    private static PlayerState playerAt(int seat) {
        return PlayerState.initial(seat, List.of(
                Card.normal(Suit.JADE, 2), Card.normal(Suit.SWORD, 3)));
    }
}
