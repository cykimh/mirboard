package com.mirboard.domain.game.onecard.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** `docs/rules-onecard.md` §8.2 — 다음 차례 표. */
class TurnOrderTest {

    private static final IntPredicate ALL = seat -> true;

    private static PlayingCard heart(int rank) {
        return PlayingCard.of(Suit.HEART, rank);
    }

    @Test
    void a_normal_card_or_a_seven_passes_to_the_next_seat_in_the_current_direction() {
        assertThat(TurnOrder.afterPlay(4, ALL, 1, +1, heart(5))).isEqualTo(2);
        assertThat(TurnOrder.afterPlay(4, ALL, 1, -1, heart(7))).isEqualTo(0);
    }

    @Test
    void an_attack_card_passes_to_the_next_seat() {
        assertThat(TurnOrder.afterPlay(4, ALL, 3, +1, heart(2))).isEqualTo(0);
    }

    @Test
    void a_jack_skips_one_seat() {
        assertThat(TurnOrder.afterPlay(4, ALL, 1, +1, heart(PlayingCard.JACK))).isEqualTo(3);
    }

    @Test
    void a_queen_goes_the_other_way() {
        // Q 는 엔진이 방향을 먼저 뒤집고 넘긴다.
        assertThat(TurnOrder.afterPlay(4, ALL, 1, -1, heart(PlayingCard.QUEEN))).isEqualTo(0);
    }

    @Test
    void a_king_gives_the_same_seat_another_turn() {
        assertThat(TurnOrder.afterPlay(4, ALL, 1, +1, heart(PlayingCard.KING))).isEqualTo(1);
    }

    @Test
    void with_two_live_seats_jack_and_queen_also_give_another_turn() {
        IntPredicate twoLeft = seat -> seat == 0 || seat == 2;
        assertThat(TurnOrder.afterPlay(4, twoLeft, 0, +1, heart(PlayingCard.JACK))).isEqualTo(0);
        assertThat(TurnOrder.afterPlay(4, twoLeft, 0, -1, heart(PlayingCard.QUEEN))).isEqualTo(0);
    }

    @Test
    void eliminated_seats_are_skipped() {
        IntPredicate withoutTwo = seat -> seat != 2;
        assertThat(TurnOrder.afterPlay(4, withoutTwo, 1, +1, heart(5))).isEqualTo(3);
        assertThat(TurnOrder.afterPlay(4, withoutTwo, 1, +1, heart(PlayingCard.JACK))).isEqualTo(0);
    }

    @Test
    void next_alive_wraps_around_both_ways() {
        assertThat(TurnOrder.nextAlive(4, ALL, 3, +1)).isEqualTo(0);
        assertThat(TurnOrder.nextAlive(4, ALL, 0, -1)).isEqualTo(3);
        // 살아 있는 좌석이 하나도 없으면 한 바퀴를 다 돌아도 못 찾는다 — 엔진이 이런 호출을 하면 버그다.
        assertThatThrownBy(() -> TurnOrder.nextAlive(4, seat -> false, 0, 1)).isInstanceOf(IllegalStateException.class);
    }
}
