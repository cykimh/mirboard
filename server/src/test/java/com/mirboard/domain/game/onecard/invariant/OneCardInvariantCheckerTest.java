package com.mirboard.domain.game.onecard.invariant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §14 — 불변식 검사기가 깨진 상태를 실제로 잡는가. */
class OneCardInvariantCheckerTest {

    private static final PlayingCard TOP = PlayingCard.of(Suit.HEART, 5);

    /** 좌석 0·1 에 각자 3장, 나머지는 뽑을 더미, 맨 위는 ♥5. */
    private static OneCardState valid() {
        List<PlayingCard> rest = new ArrayList<>(Deck.all());
        rest.remove(TOP);
        List<PlayingCard> seatZero = new ArrayList<>(rest.subList(0, 3));
        List<PlayingCard> seatOne = new ArrayList<>(rest.subList(3, 6));
        List<PlayingCard> pile = new ArrayList<>(rest.subList(6, rest.size()));
        return new OneCardState(List.of(seatZero, seatOne), pile, List.of(TOP), 0, 1, null, 0, null, List.of(),
                0, 0, 1, null);
    }

    private static OneCardState with(OneCardState s, List<PlayingCard> drawPile, int attackStack,
                                     List<Elimination> eliminations, int turnSeat) {
        return new OneCardState(s.hands(), drawPile, s.discardPile(), turnSeat, s.direction(), s.declaredSuit(),
                attackStack, s.race(), eliminations, s.passStreak(), s.turnCount(), s.version(), s.result());
    }

    @Test
    void a_valid_table_passes() {
        assertThatCode(() -> OneCardInvariantChecker.check(valid())).doesNotThrowAnyException();
    }

    @Test
    void a_lost_card_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile().subList(1, s.drawPile().size()), 0, List.of(), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("card conservation");
    }

    @Test
    void an_eliminated_seat_holding_cards_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 0, List.of(new Elimination(1, Elimination.Reason.DESERTED, 3)), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("still holds cards");
    }

    @Test
    void a_pending_attack_on_a_non_attack_card_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 2, List.of(), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("attack pending");
    }

    @Test
    void a_turn_on_a_missing_seat_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 0, List.of(), 5);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("turn seat");
    }
}
