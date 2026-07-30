package com.mirboard.domain.game.skullking.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 액션 합법성 검증 (§5 입찰, §6.2 follow 의무). */
class ActionValidatorTest {

    private static SkullCard green(int rank) {
        return SkullCard.of(SkullSuit.GREEN, rank);
    }

    private static SkullCard yellow(int rank) {
        return SkullCard.of(SkullSuit.YELLOW, rank);
    }

    private static SkullCard black(int rank) {
        return SkullCard.of(SkullSuit.BLACK, rank);
    }

    private static SkullKingState.Bidding bidding(List<List<SkullCard>> hands) {
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < hands.size(); seat++) {
            players.add(PlayerState.initial(seat, hands.get(seat)));
        }
        return new SkullKingState.Bidding(hands.get(0).size(), players, 0);
    }

    /** 좌석 0 이 리드하는 플레이 상태. hands 는 좌석 순. */
    private static SkullKingState.Playing playing(List<List<SkullCard>> hands,
                                                  List<PlayedCard> alreadyPlayed) {
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < hands.size(); seat++) {
            players.add(PlayerState.initial(seat, hands.get(seat)).withBid(0));
        }
        TrickState trick = TrickState.lead(0);
        for (PlayedCard pc : alreadyPlayed) {
            trick = trick.with(pc);
        }
        return new SkullKingState.Playing(3, players, 0, trick);
    }

    private static RejectionReason reasonOf(ThrowingCall call) {
        try {
            call.run();
        } catch (SkullKingActionRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("expected rejection but none was thrown");
    }

    interface ThrowingCall {
        void run();
    }

    @Nested
    @DisplayName("PLACE_BID (§5)")
    class PlaceBid {

        @Test
        void a_bid_within_zero_to_hand_size_is_accepted() {
            SkullKingState.Bidding state = bidding(List.of(
                    List.of(green(1), green(2), green(3)), List.of(yellow(1), yellow(2), yellow(3))));

            for (int bid = 0; bid <= 3; bid++) {
                int b = bid;
                assertThatCode(() -> ActionValidator.validate(state, 0,
                        new SkullKingAction.PlaceBid(b))).doesNotThrowAnyException();
            }
        }

        @Test
        void a_negative_bid_is_rejected() {
            SkullKingState.Bidding state = bidding(List.of(List.of(green(1)), List.of(yellow(1))));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    new SkullKingAction.PlaceBid(-1))))
                    .isEqualTo(RejectionReason.BID_OUT_OF_RANGE);
        }

        @Test
        void a_bid_above_hand_size_is_rejected() {
            SkullKingState.Bidding state = bidding(List.of(
                    List.of(green(1), green(2)), List.of(yellow(1), yellow(2))));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    new SkullKingAction.PlaceBid(3))))
                    .isEqualTo(RejectionReason.BID_OUT_OF_RANGE);
        }

        /**
         * 명세 함정 #3 의 그 케이스 — 8인 라운드 10 은 손패가 8장이므로 예측 상한도 8이다.
         * 라운드 번호(10)로 상한을 잡으면 달성 불가능한 9·10 을 허용하게 된다.
         */
        @Test
        void hand_size_not_round_number_bounds_the_bid() {
            List<SkullCard> eightCards = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                eightCards.add(green(i));
            }
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, eightCards), PlayerState.initial(1, eightCards));
            SkullKingState.Bidding round10 = new SkullKingState.Bidding(10, players, 0);

            assertThatCode(() -> ActionValidator.validate(round10, 0,
                    new SkullKingAction.PlaceBid(8))).doesNotThrowAnyException();
            assertThat(reasonOf(() -> ActionValidator.validate(round10, 0,
                    new SkullKingAction.PlaceBid(9))))
                    .as("라운드 10 이라도 손패가 8장이면 상한은 8")
                    .isEqualTo(RejectionReason.BID_OUT_OF_RANGE);
        }

        @Test
        void bidding_twice_is_rejected() {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(green(1))).withBid(1),
                    PlayerState.initial(1, List.of(yellow(1))));
            SkullKingState.Bidding state = new SkullKingState.Bidding(1, players, 0);

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    new SkullKingAction.PlaceBid(0))))
                    .isEqualTo(RejectionReason.ALREADY_BID);
        }

        @Test
        void bidding_outside_the_bidding_phase_is_rejected() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(green(1)), List.of(yellow(1))), List.of());

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    new SkullKingAction.PlaceBid(0))))
                    .isEqualTo(RejectionReason.NOT_IN_BIDDING_PHASE);
        }

        @Test
        void an_out_of_range_seat_is_rejected() {
            SkullKingState.Bidding state = bidding(List.of(List.of(green(1)), List.of(yellow(1))));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 5,
                    new SkullKingAction.PlaceBid(0))))
                    .isEqualTo(RejectionReason.INVALID_STATE_FOR_ACTION);
        }
    }

    @Nested
    @DisplayName("PLAY_CARD — 기본 가드")
    class PlayCardBasics {

        @Test
        void playing_out_of_turn_is_rejected() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(green(1)), List.of(yellow(1))), List.of());

            assertThat(reasonOf(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(yellow(1)))))
                    .isEqualTo(RejectionReason.NOT_YOUR_TURN);
        }

        @Test
        void playing_a_card_not_in_hand_is_rejected() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(green(1)), List.of(yellow(1))), List.of());

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    SkullKingAction.PlayCard.of(black(9)))))
                    .isEqualTo(RejectionReason.CARD_NOT_OWNED);
        }

        @Test
        void playing_during_the_bidding_phase_is_rejected() {
            SkullKingState.Bidding state = bidding(List.of(List.of(green(1)), List.of(yellow(1))));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    SkullKingAction.PlayCard.of(green(1)))))
                    .isEqualTo(RejectionReason.NOT_IN_PLAYING_PHASE);
        }

        @Test
        void tigress_without_a_declaration_is_rejected_with_a_client_code() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(SkullCard.tigress()), List.of(yellow(1))), List.of());

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    new SkullKingAction.PlayCard(SkullCard.tigress(), null))))
                    .isEqualTo(RejectionReason.INVALID_TIGRESS_DECLARATION);
        }

        @Test
        void a_declaration_on_a_non_tigress_card_is_rejected() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(green(1)), List.of(yellow(1))), List.of());

            assertThat(reasonOf(() -> ActionValidator.validate(state, 0,
                    new SkullKingAction.PlayCard(green(1), TigressMode.PIRATE))))
                    .isEqualTo(RejectionReason.INVALID_TIGRESS_DECLARATION);
        }

        @Test
        void a_declared_tigress_is_accepted() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(SkullCard.tigress()), List.of(yellow(1))), List.of());

            assertThatCode(() -> ActionValidator.validate(state, 0,
                    SkullKingAction.PlayCard.tigress(TigressMode.PIRATE)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("§6.2 follow 의무 — 양성 3건 / 음성 3건")
    class FollowObligation {

        /** 좌석 1 차례. 좌석 0 이 초록 5 로 리드했다. */
        private SkullKingState.Playing greenLed(List<SkullCard> seatOneHand) {
            return playing(
                    List.of(List.of(), seatOneHand),
                    List.of(PlayedCard.of(0, green(5))));
        }

        // ---- 양성: 합법인 플레이 ----

        @Test
        void positive_following_the_lead_suit_is_legal() {
            SkullKingState.Playing state = greenLed(List.of(green(9), yellow(2)));

            assertThatCode(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(green(9)))).doesNotThrowAnyException();
        }

        @Test
        void positive_a_special_card_is_always_legal_even_holding_the_lead_suit() {
            SkullKingState.Playing state = greenLed(List.of(green(9), SkullCard.pirate()));

            assertThatCode(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(SkullCard.pirate())))
                    .as("특수 카드는 follow 의무의 예외다")
                    .doesNotThrowAnyException();
        }

        @Test
        void positive_any_suit_is_legal_when_void_in_the_lead_suit() {
            SkullKingState.Playing state = greenLed(List.of(yellow(14), black(2)));

            assertThatCode(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(yellow(14)))).doesNotThrowAnyException();
            assertThatCode(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(black(2)))).doesNotThrowAnyException();
        }

        @Test
        void positive_escape_is_legal_while_holding_the_lead_suit() {
            SkullKingState.Playing state = greenLed(List.of(green(9), SkullCard.escape()));

            assertThatCode(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(SkullCard.escape())))
                    .doesNotThrowAnyException();
        }

        // ---- 음성: 거절돼야 하는 플레이 ----

        @Test
        void negative_off_suit_while_holding_the_lead_suit_is_rejected() {
            SkullKingState.Playing state = greenLed(List.of(green(9), yellow(2)));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(yellow(2)))))
                    .isEqualTo(RejectionReason.MUST_FOLLOW_LEAD_SUIT);
        }

        @Test
        void negative_black_off_suit_while_holding_the_lead_suit_is_rejected() {
            SkullKingState.Playing state = greenLed(List.of(green(9), black(14)));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(black(14)))))
                    .as("검정이 으뜸패라도 follow 의무는 면제되지 않는다")
                    .isEqualTo(RejectionReason.MUST_FOLLOW_LEAD_SUIT);
        }

        @Test
        void negative_holding_several_lead_suit_cards_still_forces_a_follow() {
            SkullKingState.Playing state = greenLed(List.of(green(1), green(9), yellow(2)));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(yellow(2)))))
                    .isEqualTo(RejectionReason.MUST_FOLLOW_LEAD_SUIT);
        }
    }

    @Nested
    @DisplayName("리드 수트 미확정 구간에는 제약이 없다 (§6.1)")
    class NoLeadSuitYet {

        @Test
        void the_leader_may_play_anything() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(green(3), yellow(9), SkullCard.skullKing()), List.of()),
                    List.of());

            for (SkullCard card : List.of(green(3), yellow(9), SkullCard.skullKing())) {
                assertThatCode(() -> ActionValidator.validate(state, 0,
                        SkullKingAction.PlayCard.of(card))).doesNotThrowAnyException();
            }
        }

        @Test
        void a_character_lead_frees_everyone_for_the_whole_trick() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(), List.of(green(3), yellow(9))),
                    List.of(PlayedCard.of(0, SkullCard.pirate())));

            assertThatCode(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(yellow(9))))
                    .as("캐릭터 리드면 리드 수트가 없으므로 아무 색이나 가능")
                    .doesNotThrowAnyException();
        }

        @Test
        void an_escape_lead_leaves_the_next_player_unconstrained() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(), List.of(green(3), yellow(9))),
                    List.of(PlayedCard.of(0, SkullCard.escape())));

            assertThatCode(() -> ActionValidator.validate(state, 1,
                    SkullKingAction.PlayCard.of(yellow(9)))).doesNotThrowAnyException();
        }

        /** §13-⑥ — 리드 수트가 트릭 도중 확정되면 그 이후 플레이어부터 의무가 생긴다. */
        @Test
        void once_a_suit_card_fixes_the_lead_suit_later_players_must_follow() {
            SkullKingState.Playing state = playing(
                    List.of(List.of(), List.of(), List.of(green(3), yellow(9))),
                    List.of(PlayedCard.of(0, SkullCard.escape()), PlayedCard.of(1, green(7))));

            assertThat(reasonOf(() -> ActionValidator.validate(state, 2,
                    SkullKingAction.PlayCard.of(yellow(9)))))
                    .as("탈출 리드였어도 초록이 나온 뒤에는 초록을 따라야 한다")
                    .isEqualTo(RejectionReason.MUST_FOLLOW_LEAD_SUIT);
            assertThatCode(() -> ActionValidator.validate(state, 2,
                    SkullKingAction.PlayCard.of(green(3)))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("거절 사유는 포트 code() 로 그대로 나간다")
    class RejectionCodes {

        @Test
        void exception_exposes_the_reason_name_as_the_client_code() {
            SkullKingActionRejectedException e =
                    new SkullKingActionRejectedException(RejectionReason.BID_OUT_OF_RANGE);

            assertThat(e.code()).isEqualTo("BID_OUT_OF_RANGE");
            assertThat(e.reason()).isEqualTo(RejectionReason.BID_OUT_OF_RANGE);
        }

        @Test
        void every_reason_has_a_stable_code() {
            Map<RejectionReason, String> codes = new java.util.EnumMap<>(RejectionReason.class);
            for (RejectionReason reason : RejectionReason.values()) {
                codes.put(reason, new SkullKingActionRejectedException(reason).code());
            }

            assertThat(codes.values()).doesNotContainNull().doesNotHaveDuplicates();
        }
    }
}
