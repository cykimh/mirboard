package com.mirboard.domain.game.skullking.invariant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.skullking.Dealer;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 불변식 체커가 <b>실제로 위반을 잡는지</b> 검증한다. 통과 케이스만 있는 체커는 아무것도
 * 보장하지 않으므로 각 불변식마다 고의로 깨뜨린 상태를 넣는다.
 */
class SkullKingInvariantCheckerTest {

    private static SkullCard green(int rank) {
        return SkullCard.of(SkullSuit.GREEN, rank);
    }

    @Nested
    @DisplayName("정상 상태는 통과한다")
    class Passing {

        @Test
        void a_freshly_dealt_bidding_state_is_valid() {
            for (int seats = 2; seats <= 8; seats++) {
                for (int round = 1; round <= 10; round++) {
                    List<PlayerState> players = Dealer.deal(round, seats, new Random(round));
                    SkullKingState state = new SkullKingState.Bidding(round, players, 0);
                    int r = round;
                    int s = seats;
                    assertThatCode(() -> SkullKingInvariantChecker.check(state))
                            .as("%d인 라운드 %d", s, r)
                            .doesNotThrowAnyException();
                }
            }
        }

        @Test
        void a_playing_state_mid_trick_is_valid() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of()).withBid(1),
                    PlayerState.initial(1, List.of(green(9))).withBid(0));
            TrickState trick = TrickState.lead(0).with(PlayedCard.of(0, green(5)));

            assertThatCode(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Playing(1, players, 0, trick)))
                    .doesNotThrowAnyException();
        }

        @Test
        void a_round_end_state_with_all_cards_in_won_tricks_is_valid() {
            PlayedCard a = PlayedCard.of(0, green(5));
            PlayedCard b = PlayedCard.of(1, green(9));
            TrickResult trick = new TrickResult(1, b, List.of(a, b));
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of()).withBid(0),
                    new PlayerState(1, List.of(), 1, List.of(trick)));

            assertThatCode(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.RoundEnd(1, players, 0, Map.of())))
                    .doesNotThrowAnyException();
        }

        /** 8인 라운드 10 은 64장만 쓴다 — 70 기준이면 여기서 거짓 위반이 난다. */
        @Test
        void eight_player_round_ten_passes_despite_six_unused_cards() {
            List<PlayerState> players = Dealer.deal(10, 8, new Random(3));

            assertThatCode(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(10, players, 0)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("카드 보존 위반을 잡는다")
    class CardConservation {

        @Test
        void a_missing_card_is_caught() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(green(1), green(2))).withBid(0),
                    PlayerState.initial(1, List.of(green(3))).withBid(0));

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(2, players, 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("card count = 3, expected 4");
        }

        @Test
        void an_extra_card_is_caught() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(green(1), green(2))).withBid(0),
                    PlayerState.initial(1, List.of(green(3), green(4), green(5))).withBid(0));

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(2, players, 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected 4");
        }

        @Test
        void a_duplicated_suit_card_is_caught() {
            // 장수는 맞지만 초록 1 이 두 장 — 색상 카드는 덱에 1장뿐이라 허용치 위반.
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(green(1), green(2))).withBid(0),
                    PlayerState.initial(1, List.of(green(1), green(3))).withBid(0));

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(2, players, 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deck holds only 1");
        }

        @Test
        void too_many_copies_of_a_special_card_are_caught() {
            // 스컬킹은 1장뿐인데 2장.
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(SkullCard.skullKing())).withBid(0),
                    PlayerState.initial(1, List.of(SkullCard.skullKing())).withBid(0));

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(1, players, 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deck holds only 1");
        }

        @Test
        void interchangeable_duplicates_within_the_allowance_are_fine() {
            // 해적 2장은 덱 허용치(5) 안이라 위반이 아니다.
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(SkullCard.pirate())).withBid(0),
                    PlayerState.initial(1, List.of(SkullCard.pirate())).withBid(0));

            assertThatCode(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(1, players, 0)))
                    .doesNotThrowAnyException();
        }

        @Test
        void cards_sitting_in_the_current_trick_still_count() {
            // 좌석 0 이 낸 카드가 트릭에 있으므로 총합은 여전히 2장이어야 한다.
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of()).withBid(0),
                    PlayerState.initial(1, List.of(green(9))).withBid(0));
            TrickState emptyTrick = TrickState.lead(0);

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Playing(1, players, 0, emptyTrick)))
                    .as("낸 카드가 트릭에도 없으면 사라진 것이다")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("card count = 1, expected 2");
        }
    }

    @Nested
    @DisplayName("구조 위반을 잡는다")
    class Structure {

        @Test
        void out_of_order_seats_are_caught() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(1, List.of(green(1))).withBid(0),
                    PlayerState.initial(0, List.of(green(2))).withBid(0));

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(1, players, 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("players[0].seat = 1");
        }

        @Test
        void a_bid_above_hand_size_is_caught() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(green(1))).withBid(5),
                    PlayerState.initial(1, List.of(green(2))).withBid(0));

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(1, players, 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("out of [0, 1]");
        }

        @Test
        void an_unset_bid_is_not_flagged() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(green(1))),
                    PlayerState.initial(1, List.of(green(2))));

            assertThatCode(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Bidding(1, players, 0)))
                    .doesNotThrowAnyException();
        }

        /**
         * 카드 보존은 성립시킨 채 턴만 깨뜨린다 — 라운드 1 / 2인이면 총 2장이 맞아야
         * 하는데, 그 2장이 좌석 1 에 몰려 있고 빈손인 좌석 0 이 차례다. 장수 검사가
         * 먼저 걸리면 턴 검사에 도달하지 못하므로 총합을 일부러 맞춰 둔다.
         */
        @Test
        void a_seat_on_turn_with_an_empty_hand_is_caught() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of()).withBid(0),
                    PlayerState.initial(1, List.of(green(9), green(3))).withBid(0));
            TrickState trick = TrickState.lead(0);

            assertThatThrownBy(() -> SkullKingInvariantChecker.check(
                    new SkullKingState.Playing(1, players, 0, trick)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("holds no cards");
        }
    }
}
