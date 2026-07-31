package com.mirboard.domain.game.skullking;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.event.SkullKingEvent;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 엔진 라이프사이클 (§3, §5, §9). */
class SkullKingEngineTest {

    private static SkullKingEngine engineFor(int seats) {
        List<Long> playerIds = new java.util.ArrayList<>();
        for (long i = 0; i < seats; i++) {
            playerIds.add(100L + i);
        }
        return new SkullKingEngine(new GameContext("room-1", playerIds));
    }

    private static List<SkullKingEvent> apply(SkullKingEngine engine,
                                              SkullKingState[] holder,
                                              int seat,
                                              SkullKingAction action) {
        SkullKingEngine.Result result = engine.apply(holder[0], seat, action);
        holder[0] = result.newState();
        return result.events();
    }

    @Nested
    @DisplayName("라운드 시작 (§3, §4)")
    class StartRound {

        @Test
        void deals_and_enters_bidding() {
            SkullKingEngine engine = engineFor(4);
            SkullKingEngine.Result result =
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1));

            assertThat(result.newState()).isInstanceOf(SkullKingState.Bidding.class);
            assertThat(result.newState().roundNumber()).isEqualTo(1);
            assertThat(result.newState().phaseName()).isEqualTo("BIDDING");
            result.newState().players().forEach(p -> assertThat(p.handSize()).isEqualTo(1));
        }

        @Test
        void emits_a_private_hand_to_every_seat_and_one_public_start() {
            SkullKingEngine engine = engineFor(4);
            List<SkullKingEvent> events =
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).events();

            assertThat(events.stream().filter(SkullKingEvent.HandDealt.class::isInstance))
                    .hasSize(4);
            assertThat(events.stream().filter(SkullKingEvent.BiddingStarted.class::isInstance))
                    .hasSize(1);
        }

        @Test
        void hand_dealt_is_private_to_its_own_seat() {
            SkullKingEngine engine = engineFor(4);
            engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).events().stream()
                    .filter(SkullKingEvent.HandDealt.class::isInstance)
                    .map(SkullKingEvent.HandDealt.class::cast)
                    .forEach(hd -> {
                        assertThat(hd.isPrivate()).isTrue();
                        assertThat(hd.privateSeat()).isEqualTo(hd.seat());
                    });
        }

        @Test
        void every_other_event_is_public() {
            SkullKingEngine engine = engineFor(4);
            engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).events().stream()
                    .filter(e -> !(e instanceof SkullKingEvent.HandDealt))
                    .forEach(e -> assertThat(e.isPrivate())
                            .as("%s", e.envelopeType())
                            .isFalse());
        }

        @Test
        void the_round_starts_at_the_match_start_seat() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState state =
                    engine.startRound(new SkullKingMatchState(3, 2, java.util.Map.of()),
                            new Random(1)).newState();

            assertThat(state.startSeat()).isEqualTo(2);
            assertThat(state.roundNumber()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("입찰 — 전원 제출 전까지 값 비공개 (§5)")
    class Bidding {

        @Test
        void every_seat_is_pending_until_it_bids() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState[] state = {
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).newState()};

            assertThat(engine.pendingSeats(state[0])).containsExactly(0, 1, 2, 3);
            apply(engine, state, 1, new SkullKingAction.PlaceBid(0));
            assertThat(engine.pendingSeats(state[0]))
                    .as("입찰은 동시 대기 — 한 명이 냈다고 나머지가 막히지 않는다")
                    .containsExactly(0, 2, 3);
        }

        @Test
        void a_submitted_bid_leaks_no_value_until_everyone_has_bid() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState[] state = {
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).newState()};

            List<SkullKingEvent> first = apply(engine, state, 0, new SkullKingAction.PlaceBid(1));

            assertThat(first).singleElement().isInstanceOf(SkullKingEvent.BidSubmitted.class);
            assertThat(first).noneMatch(SkullKingEvent.BidsRevealed.class::isInstance);
        }

        @Test
        void the_last_bid_reveals_everyone_at_once_and_starts_play() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState[] state = {
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).newState()};

            apply(engine, state, 0, new SkullKingAction.PlaceBid(1));
            apply(engine, state, 1, new SkullKingAction.PlaceBid(0));
            apply(engine, state, 2, new SkullKingAction.PlaceBid(1));
            List<SkullKingEvent> last = apply(engine, state, 3, new SkullKingAction.PlaceBid(0));

            SkullKingEvent.BidsRevealed revealed = last.stream()
                    .filter(SkullKingEvent.BidsRevealed.class::isInstance)
                    .map(SkullKingEvent.BidsRevealed.class::cast)
                    .findFirst().orElseThrow();

            assertThat(revealed.bids()).containsEntry(0, 1).containsEntry(1, 0)
                    .containsEntry(2, 1).containsEntry(3, 0);
            assertThat(last).anyMatch(SkullKingEvent.PlayingStarted.class::isInstance);
            assertThat(state[0]).isInstanceOf(SkullKingState.Playing.class);
        }

        @Test
        void play_begins_at_the_round_start_seat() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState[] state = {
                    engine.startRound(new SkullKingMatchState(1, 2, java.util.Map.of()),
                            new Random(1)).newState()};

            for (int seat = 0; seat < 4; seat++) {
                apply(engine, state, seat, new SkullKingAction.PlaceBid(0));
            }

            assertThat(((SkullKingState.Playing) state[0]).currentTurnSeat()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("트릭 진행 (§9)")
    class Tricks {

        /** 좌석 2명, 손패를 직접 심어 결정적으로 검증한다. */
        private SkullKingState.Playing twoSeatPlaying(SkullCard seat0, SkullCard seat1) {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(seat0)).withBid(1),
                    PlayerState.initial(1, List.of(seat1)).withBid(0));
            return new SkullKingState.Playing(1, players, 0, TrickState.lead(0));
        }

        @Test
        void the_winner_collects_the_trick_and_the_round_ends_when_hands_empty() {
            SkullKingEngine engine = engineFor(2);
            SkullKingState[] state = {twoSeatPlaying(
                    SkullCard.of(SkullSuit.GREEN, 5), SkullCard.of(SkullSuit.GREEN, 9))};

            apply(engine, state, 0, SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 5)));
            List<SkullKingEvent> last = apply(engine, state, 1,
                    SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 9)));

            assertThat(last).anyMatch(SkullKingEvent.TrickTaken.class::isInstance);
            assertThat(state[0]).isInstanceOf(SkullKingState.RoundEnd.class);

            SkullKingState.RoundEnd end = (SkullKingState.RoundEnd) state[0];
            assertThat(end.players().get(1).tricksWonCount()).isEqualTo(1);
            assertThat(end.players().get(0).tricksWonCount()).isZero();
        }

        @Test
        void the_trick_winner_leads_the_next_trick() {
            SkullKingEngine engine = engineFor(2);
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(
                            SkullCard.of(SkullSuit.GREEN, 5), SkullCard.of(SkullSuit.GREEN, 6)))
                            .withBid(1),
                    PlayerState.initial(1, List.of(
                            SkullCard.of(SkullSuit.GREEN, 9), SkullCard.of(SkullSuit.GREEN, 2)))
                            .withBid(1));
            SkullKingState[] state = {
                    new SkullKingState.Playing(2, players, 0, TrickState.lead(0))};

            apply(engine, state, 0, SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 5)));
            apply(engine, state, 1, SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 9)));

            assertThat(((SkullKingState.Playing) state[0]).currentTurnSeat())
                    .as("좌석 1 이 이겼으므로 다음 트릭도 좌석 1 이 리드")
                    .isEqualTo(1);
        }

        @Test
        void the_round_end_state_carries_scores_for_every_seat() {
            SkullKingEngine engine = engineFor(2);
            SkullKingState[] state = {twoSeatPlaying(
                    SkullCard.of(SkullSuit.GREEN, 5), SkullCard.of(SkullSuit.GREEN, 9))};

            apply(engine, state, 0, SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 5)));
            apply(engine, state, 1, SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 9)));

            SkullKingState.RoundEnd end = (SkullKingState.RoundEnd) state[0];
            assertThat(end.scores()).containsOnlyKeys(0, 1);
            // 좌석 0: bid 1, won 0 → -10.  좌석 1: bid 0, won 1 → -R(1)×10 = -10.
            assertThat(end.totalsBySeat()).containsEntry(0, -10).containsEntry(1, -10);
        }

        @Test
        void nobody_is_pending_once_the_round_has_ended() {
            SkullKingEngine engine = engineFor(2);
            SkullKingState[] state = {twoSeatPlaying(
                    SkullCard.of(SkullSuit.GREEN, 5), SkullCard.of(SkullSuit.GREEN, 9))};

            apply(engine, state, 0, SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 5)));
            apply(engine, state, 1, SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.GREEN, 9)));

            assertThat(engine.pendingSeats(state[0])).isEmpty();
            assertThat(engine.isRoundOver(state[0])).isTrue();
        }
    }

    @Nested
    @DisplayName("합법 액션 / 타임아웃")
    class LegalActions {

        @Test
        void bidding_offers_zero_through_hand_size() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState state =
                    engine.startRound(new SkullKingMatchState(3, 0, java.util.Map.of()),
                            new Random(1)).newState();

            assertThat(engine.legalActions(state, 0))
                    .as("라운드 3 → 손패 3장 → 0~3 의 네 가지")
                    .hasSize(4);
        }

        @Test
        void a_seat_that_already_bid_has_no_legal_action() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState[] state = {
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).newState()};

            apply(engine, state, 0, new SkullKingAction.PlaceBid(0));

            assertThat(engine.legalActions(state[0], 0)).isEmpty();
        }

        @Test
        void only_the_seat_on_turn_has_play_actions() {
            SkullKingEngine engine = engineFor(2);
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(SkullCard.of(SkullSuit.GREEN, 5))).withBid(0),
                    PlayerState.initial(1, List.of(SkullCard.of(SkullSuit.GREEN, 9))).withBid(0));
            SkullKingState state = new SkullKingState.Playing(1, players, 0, TrickState.lead(0));

            assertThat(engine.legalActions(state, 0)).hasSize(1);
            assertThat(engine.legalActions(state, 1)).isEmpty();
        }

        @Test
        void a_tigress_in_hand_yields_two_actions() {
            SkullKingEngine engine = engineFor(2);
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(SkullCard.tigress())).withBid(0),
                    PlayerState.initial(1, List.of(SkullCard.of(SkullSuit.GREEN, 9))).withBid(0));
            SkullKingState state = new SkullKingState.Playing(1, players, 0, TrickState.lead(0));

            assertThat(engine.legalActions(state, 0))
                    .as("선언이 정체성을 바꾸므로 같은 카드가 두 액션이 된다")
                    .hasSize(2);
        }

        @Test
        void duplicate_cards_collapse_into_one_action() {
            SkullKingEngine engine = engineFor(2);
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(SkullCard.pirate(), SkullCard.pirate()))
                            .withBid(0),
                    PlayerState.initial(1, List.of(SkullCard.of(SkullSuit.GREEN, 9))).withBid(0));
            SkullKingState state = new SkullKingState.Playing(1, players, 0, TrickState.lead(0));

            assertThat(engine.legalActions(state, 0)).hasSize(1);
        }

        @Test
        void timeout_during_bidding_bids_zero() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState state =
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).newState();

            assertThat(engine.timeoutAction(state, 0))
                    .isEqualTo(new SkullKingAction.PlaceBid(0));
        }

        /** 범위 밖 좌석은 단계와 무관하게 null — Bidding 만 IOOBE 로 터지던 비대칭 회귀 가드. */
        @Test
        void timeout_for_an_out_of_range_seat_is_null_in_every_phase() {
            SkullKingEngine engine = engineFor(4);
            SkullKingState bidding =
                    engine.startRound(SkullKingMatchState.initial(4, 0), new Random(1)).newState();

            assertThat(engine.timeoutAction(bidding, -1)).isNull();
            assertThat(engine.timeoutAction(bidding, 4)).isNull();
        }

        @Test
        void timeout_during_play_prefers_an_escape() {
            SkullKingEngine engine = engineFor(2);
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(
                            SkullCard.of(SkullSuit.BLACK, 14), SkullCard.escape())).withBid(0),
                    PlayerState.initial(1, List.of(SkullCard.of(SkullSuit.GREEN, 9))).withBid(0));
            SkullKingState state = new SkullKingState.Playing(1, players, 0, TrickState.lead(0));

            assertThat(engine.timeoutAction(state, 0))
                    .isEqualTo(SkullKingAction.PlayCard.of(SkullCard.escape()));
        }

        @Test
        void timeout_is_deterministic() {
            SkullKingEngine engine = engineFor(2);
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(
                            SkullCard.of(SkullSuit.BLACK, 14),
                            SkullCard.of(SkullSuit.GREEN, 2))).withBid(0),
                    PlayerState.initial(1, List.of(SkullCard.of(SkullSuit.GREEN, 9))).withBid(0));
            SkullKingState state = new SkullKingState.Playing(1, players, 0, TrickState.lead(0));

            assertThat(engine.timeoutAction(state, 0))
                    .isEqualTo(engine.timeoutAction(state, 0));
        }

        @Test
        void no_legal_action_at_round_end() {
            SkullKingEngine engine = engineFor(2);
            SkullKingState state = new SkullKingState.RoundEnd(1,
                    List.of(PlayerState.initial(0, List.of()), PlayerState.initial(1, List.of())),
                    0, java.util.Map.of());

            assertThat(engine.legalActions(state, 0)).isEmpty();
            assertThat(engine.timeoutAction(state, 0)).isNull();
        }
    }

    @Nested
    @DisplayName("라운드 정산 (§10, §12)")
    class Settlement {

        private SkullKingState.RoundEnd roundEndWith(int roundNumber, int seat0Total) {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of()), PlayerState.initial(1, List.of()));
            return new SkullKingState.RoundEnd(roundNumber, players, 0, java.util.Map.of(
                    0, new com.mirboard.domain.game.skullking.scoring.RoundScore(0, 0, seat0Total, 0),
                    1, new com.mirboard.domain.game.skullking.scoring.RoundScore(1, 0, -10, 0)));
        }

        @Test
        void accumulates_into_the_match_state_and_advances_the_round() {
            SkullKingEngine engine = engineFor(2);
            SkullKingEngine.Settlement settled =
                    engine.settleRound(roundEndWith(1, 10), SkullKingMatchState.initial(2, 0));

            assertThat(settled.matchState().roundNumber()).isEqualTo(2);
            assertThat(settled.matchState().cumulativeScores())
                    .containsEntry(0, 10).containsEntry(1, -10);
            assertThat(settled.events()).anyMatch(SkullKingEvent.RoundEnded.class::isInstance);
        }

        @Test
        void does_not_end_the_match_before_round_ten() {
            SkullKingEngine engine = engineFor(2);
            SkullKingEngine.Settlement settled =
                    engine.settleRound(roundEndWith(9, 10), new SkullKingMatchState(9, 0,
                            java.util.Map.of(0, 0, 1, 0)));

            assertThat(settled.matchState().isMatchOver()).isFalse();
            assertThat(settled.events()).noneMatch(SkullKingEvent.MatchEnded.class::isInstance);
        }

        @Test
        void ends_the_match_after_round_ten() {
            SkullKingEngine engine = engineFor(2);
            SkullKingEngine.Settlement settled =
                    engine.settleRound(roundEndWith(10, 10), new SkullKingMatchState(10, 0,
                            java.util.Map.of(0, 50, 1, 20)));

            assertThat(settled.matchState().isMatchOver()).isTrue();
            SkullKingEvent.MatchEnded ended = settled.events().stream()
                    .filter(SkullKingEvent.MatchEnded.class::isInstance)
                    .map(SkullKingEvent.MatchEnded.class::cast)
                    .findFirst().orElseThrow();

            assertThat(ended.winners()).containsExactly(0);
            assertThat(ended.roundsPlayed()).isEqualTo(10);
            assertThat(ended.finalScores()).containsEntry(0, 60).containsEntry(1, 10);
        }

        @Test
        void a_tie_at_the_top_produces_joint_winners() {
            SkullKingEngine engine = engineFor(2);
            SkullKingEngine.Settlement settled =
                    engine.settleRound(roundEndWith(10, 10), new SkullKingMatchState(10, 0,
                            java.util.Map.of(0, 0, 1, 20)));

            SkullKingEvent.MatchEnded ended = settled.events().stream()
                    .filter(SkullKingEvent.MatchEnded.class::isInstance)
                    .map(SkullKingEvent.MatchEnded.class::cast)
                    .findFirst().orElseThrow();

            assertThat(ended.winners()).containsExactly(0, 1);
        }
    }
}
