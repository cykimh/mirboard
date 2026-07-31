package com.mirboard.domain.game.skullking;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 카드 분배 (§4, §13-⑭). */
class DealerTest {

    /** 명세 §4 표 전수 — 인원 2~8 × 라운드 1~10 = 70건. */
    @Test
    void hand_size_table_matches_the_spec_for_every_seat_count_and_round() {
        for (int seats = 2; seats <= 8; seats++) {
            for (int round = 1; round <= 10; round++) {
                int expected = Math.min(round, 70 / seats);
                assertThat(Dealer.handSize(round, seats))
                        .as("%d인 라운드 %d", seats, round)
                        .isEqualTo(expected);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7})
    void seat_counts_up_to_seven_always_deal_the_round_number(int seats) {
        for (int round = 1; round <= 10; round++) {
            assertThat(Dealer.handSize(round, seats))
                    .as("%d인 라운드 %d 는 예외 없이 라운드 번호만큼", seats, round)
                    .isEqualTo(round);
        }
    }

    /** 8인 라운드 9·10 만 어긋난다 — 필요 72·80장 > 70장. */
    @Test
    void eight_players_cap_at_eight_cards_in_rounds_nine_and_ten() {
        for (int round = 1; round <= 8; round++) {
            assertThat(Dealer.handSize(round, 8)).isEqualTo(round);
        }
        assertThat(Dealer.handSize(9, 8)).isEqualTo(8);
        assertThat(Dealer.handSize(10, 8)).isEqualTo(8);
    }

    /** 7인 라운드 10 은 70장을 정확히 소진하므로 정상이다. */
    @Test
    void seven_players_round_ten_uses_the_whole_deck_exactly() {
        assertThat(Dealer.handSize(10, 7)).isEqualTo(10);
        assertThat(Dealer.handSize(10, 7) * 7).isEqualTo(Deck.SIZE);
    }

    @Test
    void eight_player_late_rounds_leave_six_cards_unused() {
        int dealt = Dealer.handSize(10, 8) * 8;

        assertThat(dealt).isEqualTo(64);
        assertThat(Deck.SIZE - dealt).isEqualTo(6);
    }

    @Test
    void deal_gives_every_seat_the_same_number_of_cards() {
        for (int seats = 2; seats <= 8; seats++) {
            for (int round : new int[] {1, 5, 9, 10}) {
                List<PlayerState> players = Dealer.deal(round, seats, new Random(round * 31L + seats));
                int expected = Dealer.handSize(round, seats);

                assertThat(players).hasSize(seats);
                for (PlayerState p : players) {
                    assertThat(p.handSize())
                            .as("%d인 라운드 %d 좌석 %d", seats, round, p.seat())
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void deal_never_hands_out_the_same_card_twice() {
        for (int seats = 2; seats <= 8; seats++) {
            final int seatCount = seats;
            List<PlayerState> players = Dealer.deal(10, seatCount, new Random(7));

            Map<SkullCard, Integer> counts = new HashMap<>();
            List<SkullCard> all = new ArrayList<>();
            players.forEach(p -> all.addAll(p.hand()));
            all.forEach(c -> counts.merge(c, 1, Integer::sum));

            counts.forEach((card, count) -> {
                int allowed = card.isSuit() ? 1 : card.special().countInDeck();
                assertThat(count)
                        .as("%d인 라운드 10 에서 %s 가 %d장", seatCount, card, count)
                        .isLessThanOrEqualTo(allowed);
            });
        }
    }

    @Test
    void seats_are_numbered_in_order() {
        List<PlayerState> players = Dealer.deal(3, 5, new Random(1));

        for (int i = 0; i < players.size(); i++) {
            assertThat(players.get(i).seat()).isEqualTo(i);
        }
    }

    @Test
    void nobody_has_bid_right_after_dealing() {
        Dealer.deal(4, 4, new Random(1))
                .forEach(p -> assertThat(p.hasBid()).isFalse());
    }

    @Test
    void the_same_seed_deals_the_same_hands() {
        assertThat(Dealer.deal(5, 4, new Random(99)))
                .isEqualTo(Dealer.deal(5, 4, new Random(99)));
    }
}
