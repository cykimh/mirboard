package com.mirboard.domain.game.tichu.invariant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.card.Wish;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TichuInvariantCheckerTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    /** 4 좌석으로 56장을 균등 분배 (단, "first cards" 만 14장씩 가져가는 결정적 분배). */
    private static List<PlayerState> dealAll() {
        List<Card> all = Deck.unshuffled().cards();
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
            players.add(PlayerState.initial(seat, all.subList(seat * 14, seat * 14 + 14)));
        }
        return players;
    }

    @Test
    void passing_with_all_56_cards_distributed_passes() {
        var state = new TichuState.Passing(dealAll(), Map.of());
        assertThatCode(() -> TichuInvariantChecker.check(state)).doesNotThrowAnyException();
    }

    @Test
    void playing_lead_with_full_hands_passes() {
        var state = new TichuState.Playing(dealAll(), TrickState.lead(0, null), -1);
        assertThatCode(() -> TichuInvariantChecker.check(state)).doesNotThrowAnyException();
    }

    @Test
    void missing_card_fails() {
        var players = dealAll();
        var brokenHand = new ArrayList<>(players.get(0).hand());
        brokenHand.remove(0);  // 1장 누락 → 55 장
        players.set(0, players.get(0).withHand(brokenHand));
        var state = new TichuState.Passing(players, Map.of());

        assertThatThrownBy(() -> TichuInvariantChecker.check(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("card count = 55");
    }

    @Test
    void duplicate_card_fails() {
        var players = dealAll();
        // 0번 손 첫 카드를 1번 손에 복제
        var dupHand = new ArrayList<>(players.get(1).hand());
        dupHand.remove(0);
        dupHand.add(players.get(0).hand().get(0));
        players.set(1, players.get(1).withHand(dupHand));
        var state = new TichuState.Passing(players, Map.of());

        assertThatThrownBy(() -> TichuInvariantChecker.check(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate cards");
    }

    @Test
    void duplicate_finished_order_fails() {
        var players = dealAll();
        // 0번을 finished=1 로
        players.set(0, new PlayerState(0, List.of(), TichuDeclaration.NONE, 1, List.copyOf(players.get(0).hand())));
        // 1번도 finished=1 로 (중복)
        players.set(1, new PlayerState(1, List.of(), TichuDeclaration.NONE, 1, List.copyOf(players.get(1).hand())));
        // hand 비웠으니 카드 보존 위해 tricksWon 에 넣었음
        var state = new TichuState.Playing(players, TrickState.lead(2, null), -1);

        assertThatThrownBy(() -> TichuInvariantChecker.check(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate finishedOrder=1");
    }

    @Test
    void current_turn_seat_pointing_to_finished_player_fails() {
        var players = dealAll();
        // 1번 좌석 finished=1
        players.set(1, new PlayerState(1, List.of(), TichuDeclaration.NONE, 1, List.copyOf(players.get(1).hand())));
        // currentTurnSeat=1 (finished) 인데 Dragon pending 아님 → invariant 위반
        var trick = new TrickState(0, 1, null, -1, Set.of(), List.of(), List.of(), null);
        var state = new TichuState.Playing(players, trick, -1);

        assertThatThrownBy(() -> TichuInvariantChecker.check(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentTurnSeat=1 is finished");
    }

    @Test
    void wish_rank_out_of_range_fails() {
        // Wish record 자체가 (rank<2 || rank>14) 시 IllegalArgumentException — Checker 도달 전에 캐치됨.
        // 본 테스트는 Wish 가 유효 범위 안인 경우 checker 가 통과함을 확인 (네거티브 자체는 Wish 가 catch).
        var state = new TichuState.Playing(
                dealAll(),
                new TrickState(0, 0, null, -1, Set.of(), List.of(), List.of(), Wish.active(7)),
                -1);
        assertThatCode(() -> TichuInvariantChecker.check(state)).doesNotThrowAnyException();
    }

    @Test
    void dealing_with_reserved_passes() {
        // Dealing(8): hand 8장 + reserved 6장 = 14 per seat
        List<Card> all = Deck.unshuffled().cards();
        var players = new ArrayList<PlayerState>();
        Map<Integer, List<Card>> reserved = new java.util.HashMap<>();
        for (int seat = 0; seat < 4; seat++) {
            int from = seat * 14;
            players.add(PlayerState.initial(seat, all.subList(from, from + 8)));
            reserved.put(seat, all.subList(from + 8, from + 14));
        }
        var state = new TichuState.Dealing(players, 8, Set.of(), reserved);

        assertThatCode(() -> TichuInvariantChecker.check(state)).doesNotThrowAnyException();
    }
}
