package com.mirboard.domain.game.skullking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.SkullKingEngine.Desertion;
import com.mirboard.domain.game.skullking.action.RejectionReason;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.action.SkullKingActionRejectedException;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.event.SkullKingEvent;
import com.mirboard.domain.game.skullking.invariant.SkullKingInvariantChecker;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 탈주 — 유령 좌석 자동조종 (D-104, §13-⑱⑲⑳). */
class SkullKingDesertionTest {

    private static SkullKingEngine engineFor(int seats) {
        List<Long> playerIds = new ArrayList<>();
        for (long i = 0; i < seats; i++) {
            playerIds.add(900L + i);
        }
        return new SkullKingEngine(new GameContext("desert-room", playerIds));
    }

    private static Set<Integer> allHuman(int seats) {
        Set<Integer> humans = new java.util.HashSet<>();
        for (int s = 0; s < seats; s++) {
            humans.add(s);
        }
        return humans;
    }

    private static SkullCard green(int rank) {
        return SkullCard.of(SkullSuit.GREEN, rank);
    }

    @Nested
    @DisplayName("입찰 단계 탈주 (§13-⑱)")
    class BiddingPhase {

        @Test
        void an_unbid_ghost_auto_bids_zero_immediately() {
            SkullKingEngine engine = engineFor(4);
            SkullKingMatchState match = SkullKingMatchState.initial(4, 0);
            SkullKingState state = engine.startRound(match, new Random(1)).newState();

            Desertion desertion = engine.desert(state, match, 3, allHuman(4));

            assertThat(desertion.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
            assertThat(desertion.events())
                    .anyMatch(e -> e instanceof SkullKingEvent.SeatDeserted sd && sd.seat() == 3)
                    .anyMatch(e -> e instanceof SkullKingEvent.BidSubmitted bs && bs.seat() == 3);
            assertThat(((SkullKingState.Bidding) desertion.newState()).awaitingSeats())
                    .containsExactly(0, 1, 2);
            SkullKingInvariantChecker.check(desertion.newState(), desertion.matchState());
        }

        @Test
        void the_last_missing_bid_from_a_ghost_triggers_the_reveal_in_one_call() {
            SkullKingEngine engine = engineFor(4);
            SkullKingMatchState match = SkullKingMatchState.initial(4, 1);
            SkullKingState[] state = {engine.startRound(match, new Random(1)).newState()};

            for (int seat : new int[] {0, 1, 2}) {
                state[0] = engine.applyAndDrain(state[0], match, seat,
                        new SkullKingAction.PlaceBid(0)).newState();
            }
            Desertion desertion = engine.desert(state[0], match, 3, allHuman(4));

            assertThat(desertion.events())
                    .anyMatch(SkullKingEvent.BidsRevealed.class::isInstance)
                    .anyMatch(SkullKingEvent.PlayingStarted.class::isInstance);
            assertThat(desertion.newState()).isInstanceOf(SkullKingState.Playing.class);
        }

        /** 이미 공개 전 제출한 예측은 절대 덮어쓰지 않는다 — 남들의 전략 근거였을 수 있다. */
        @Test
        void an_already_submitted_bid_is_never_overwritten() {
            SkullKingEngine engine = engineFor(4);
            SkullKingMatchState match = SkullKingMatchState.initial(4, 0);
            SkullKingState[] state = {engine.startRound(match, new Random(1)).newState()};

            state[0] = engine.applyAndDrain(state[0], match, 3,
                    new SkullKingAction.PlaceBid(1)).newState();
            Desertion desertion = engine.desert(state[0], match, 3, allHuman(4));

            assertThat(desertion.events())
                    .noneMatch(SkullKingEvent.BidSubmitted.class::isInstance);
            assertThat(((SkullKingState.Bidding) desertion.newState()).players().get(3).bid())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("플레이 단계 탈주 — 트릭 도중·유령 리드 (§13-⑱)")
    class PlayingPhase {

        /**
         * 3인 라운드 2 를 수작업 구성: 좌석 0 이 스컬킹을 이미 냈고 탈주한다. 유령이 낸
         * 카드는 유효하게 남고, 트릭을 이기면 다음 트릭도 유령이 자동으로 리드한다.
         */
        @Test
        void a_mid_trick_ghost_card_stays_valid_and_a_winning_ghost_leads_the_next_trick() {
            SkullKingEngine engine = engineFor(3);
            SkullKingMatchState match = new SkullKingMatchState(2, 0, Map.of(0, 0, 1, 0, 2, 0));
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, List.of(SkullCard.escape())).withBid(2),
                    PlayerState.initial(1, List.of(SkullCard.of(SkullSuit.BLACK, 5), green(2)))
                            .withBid(1),
                    PlayerState.initial(2, List.of(green(3), green(4))).withBid(0));
            TrickState trick = TrickState.lead(0).with(PlayedCard.of(0, SkullCard.skullKing()));
            SkullKingState state = new SkullKingState.Playing(2, players, 0, trick);

            Desertion desertion = engine.desert(state, match, 0, allHuman(3));
            assertThat(desertion.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
            // 진행 중 트릭은 조작하지 않는다 — 차례가 사람(1)이라 드레인 이벤트도 없다.
            assertThat(((SkullKingState.Playing) desertion.newState()).currentTurnSeat())
                    .isEqualTo(1);

            SkullKingMatchState after = desertion.matchState();
            SkullKingState s = engine.applyAndDrain(desertion.newState(), after, 1,
                    SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.BLACK, 5))).newState();
            SkullKingEngine.Result closing = engine.applyAndDrain(s, after, 2,
                    SkullKingAction.PlayCard.of(green(3)));

            // 스컬킹이 트릭을 이겼고(유령 좌석 0), 유령이 다음 트릭을 탈출로 자동 리드했다.
            assertThat(closing.events())
                    .anyMatch(e -> e instanceof SkullKingEvent.TrickTaken tt && tt.winnerSeat() == 0)
                    .anyMatch(e -> e instanceof SkullKingEvent.CardPlayed cp
                            && cp.seat() == 0 && cp.card().equals(SkullCard.escape()));
            assertThat(((SkullKingState.Playing) closing.newState()).currentTurnSeat())
                    .isEqualTo(1);
            SkullKingInvariantChecker.check(closing.newState(), after);
        }

        @Test
        void a_human_action_from_a_deserted_seat_is_rejected_with_seat_deserted() {
            SkullKingEngine engine = engineFor(4);
            SkullKingMatchState match = SkullKingMatchState.initial(4, 0).withSeatDeserted(2);
            SkullKingState state = engine.startRound(match, new Random(1)).newState();

            assertThatThrownBy(() -> engine.applyAndDrain(state, match, 2,
                    new SkullKingAction.PlaceBid(0)))
                    .isInstanceOf(SkullKingActionRejectedException.class)
                    .extracting(e -> ((SkullKingActionRejectedException) e).reason())
                    .isEqualTo(RejectionReason.SEAT_DESERTED);
        }
    }

    @Nested
    @DisplayName("조기 종료 (§13-⑲) · 승자 제외 (§13-⑳)")
    class EarlyEnd {

        /** 2인에서 1명 탈주 — 점수가 뒤지던 생존자가 단독 승자가 된다. */
        @Test
        void a_two_player_desertion_ends_the_match_with_the_survivor_as_sole_winner() {
            SkullKingEngine engine = engineFor(2);
            SkullKingMatchState match = SkullKingMatchState.initial(2, 0)
                    .withRoundScored(Map.of(0, 50, 1, 10), 2);
            SkullKingState state = engine.startRound(match, new Random(1)).newState();

            Desertion desertion = engine.desert(state, match, 0, allHuman(2));

            assertThat(desertion.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
            assertThat(desertion.matchState().isMatchOver()).isTrue();
            SkullKingEvent.MatchEnded ended = desertion.events().stream()
                    .filter(SkullKingEvent.MatchEnded.class::isInstance)
                    .map(SkullKingEvent.MatchEnded.class::cast)
                    .findFirst().orElseThrow();
            assertThat(ended.winners())
                    .as("누적 50 vs 10 이라도 탈주자는 후보에서 제외된다")
                    .containsExactly(1);
            assertThat(ended.finalScores()).containsEntry(0, 50).containsEntry(1, 10);
            assertThat(ended.roundsPlayed()).as("완주한 라운드는 1개뿐").isEqualTo(1);
        }

        @Test
        void losing_the_last_human_ends_the_match_even_with_enough_seats() {
            SkullKingEngine engine = engineFor(4);
            SkullKingMatchState match = SkullKingMatchState.initial(4, 0);
            SkullKingState state = engine.startRound(match, new Random(1)).newState();

            Desertion desertion = engine.desert(state, match, 2, Set.of(2));

            assertThat(desertion.outcome())
                    .as("좌석은 3개 남지만 사람이 0 — 봇만 남은 매치는 종료")
                    .isEqualTo(Desertion.Outcome.MATCH_ENDED);
        }

        @Test
        void successive_desertions_accumulate_until_the_threshold_ends_the_match() {
            SkullKingEngine engine = engineFor(4);
            SkullKingMatchState match = SkullKingMatchState.initial(4, 0);
            SkullKingState state = engine.startRound(match, new Random(1)).newState();

            Desertion first = engine.desert(state, match, 0, allHuman(4));
            assertThat(first.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);

            Desertion second = engine.desert(first.newState(), first.matchState(), 1, allHuman(4));
            assertThat(second.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);

            Desertion third = engine.desert(second.newState(), second.matchState(), 2, allHuman(4));
            assertThat(third.outcome()).isEqualTo(Desertion.Outcome.MATCH_ENDED);
            assertThat(third.events().stream()
                    .filter(SkullKingEvent.MatchEnded.class::isInstance)
                    .map(SkullKingEvent.MatchEnded.class::cast)
                    .findFirst().orElseThrow().winners()).containsExactly(3);
        }
    }

    @Nested
    @DisplayName("멱등 가드 3연")
    class Guards {

        private final SkullKingEngine engine = engineFor(2);
        private final SkullKingMatchState match = SkullKingMatchState.initial(2, 0);
        private final SkullKingState state = engine.startRound(match, new Random(1)).newState();

        @Test
        void a_second_desertion_of_the_same_seat_is_a_no_op() {
            SkullKingMatchState deserted = match.withSeatDeserted(0);

            Desertion again = engine.desert(state, deserted, 0, allHuman(2));

            assertThat(again.outcome()).isEqualTo(Desertion.Outcome.NOT_APPLICABLE);
            assertThat(again.events()).isEmpty();
            assertThat(again.matchState()).isSameAs(deserted);
        }

        @Test
        void a_seat_that_never_joined_the_match_is_a_no_op() {
            assertThat(engine.desert(state, match, 9, allHuman(2)).outcome())
                    .isEqualTo(Desertion.Outcome.NOT_APPLICABLE);
        }

        @Test
        void leaving_a_finished_match_is_not_desertion() {
            SkullKingMatchState over = match.abandoned();

            assertThat(engine.desert(state, over, 0, allHuman(2)).outcome())
                    .as("매치 종료 후 퇴장은 정상 퇴장이다")
                    .isEqualTo(Desertion.Outcome.NOT_APPLICABLE);
        }
    }

    @Nested
    @DisplayName("라운드 경계 — 유령이 새 라운드를 막지 않는다")
    class RoundBoundary {

        /** §13-⑮ 회전이 유령 좌석에 떨어져도 드레인이 입찰과 리드를 즉시 처리한다. */
        @Test
        void a_new_round_starting_at_a_ghost_seat_does_not_stall() {
            SkullKingEngine engine = engineFor(3);
            SkullKingMatchState match = SkullKingMatchState.initial(3, 0).withSeatDeserted(0);

            SkullKingEngine.Result started = engine.startRoundAndDrain(match, new Random(5));

            assertThat(started.events())
                    .anyMatch(e -> e instanceof SkullKingEvent.BidSubmitted bs && bs.seat() == 0);
            assertThat(((SkullKingState.Bidding) started.newState()).awaitingSeats())
                    .containsExactly(1, 2);
            SkullKingInvariantChecker.check(started.newState(), match);

            // 남은 두 사람이 입찰하면 공개 후 유령(시작 좌석 0)이 첫 카드까지 자동으로 낸다.
            SkullKingState state = started.newState();
            state = engine.applyAndDrain(state, match, 1, new SkullKingAction.PlaceBid(0)).newState();
            SkullKingEngine.Result revealed =
                    engine.applyAndDrain(state, match, 2, new SkullKingAction.PlaceBid(0));

            assertThat(revealed.events())
                    .anyMatch(e -> e instanceof SkullKingEvent.CardPlayed cp && cp.seat() == 0);
            assertThat(((SkullKingState.Playing) revealed.newState()).currentTurnSeat())
                    .isEqualTo(1);
            SkullKingInvariantChecker.check(revealed.newState(), match);
        }
    }

    @Nested
    @DisplayName("탈주 포함 풀매치 시뮬레이션")
    class FullMatch {

        /**
         * 4인 매치 — 4라운드 시작 직후 좌석 2 가 탈주. 남은 3인이 10라운드를 완주하고,
         * 매 액션 뒤 2-인자 불변식(드레인 누락 감지 포함)을 통과한다.
         */
        @Test
        void a_match_with_a_mid_match_desertion_completes_all_ten_rounds() {
            SkullKingEngine engine = engineFor(4);
            Random rng = new Random(20260731L);
            SkullKingMatchState match = SkullKingMatchState.initial(4, rng.nextInt(4));
            List<SkullKingEvent> all = new ArrayList<>();
            boolean deserted = false;

            while (!match.isMatchOver()) {
                SkullKingEngine.Result started = engine.startRoundAndDrain(match, rng);
                SkullKingState state = started.newState();
                all.addAll(started.events());

                if (match.roundNumber() == 4 && !deserted) {
                    Desertion desertion = engine.desert(state, match, 2, allHuman(4));
                    assertThat(desertion.outcome()).isEqualTo(Desertion.Outcome.CONTINUED);
                    state = desertion.newState();
                    match = desertion.matchState();
                    all.addAll(desertion.events());
                    deserted = true;
                }
                SkullKingInvariantChecker.check(state, match);

                int guard = 0;
                while (!engine.isRoundOver(state)) {
                    if (++guard > 5_000) {
                        throw new AssertionError("round stalled: " + state.phaseName());
                    }
                    List<Integer> pending = engine.pendingSeats(state);
                    assertThat(pending).isNotEmpty();
                    assertThat(pending)
                            .as("유령이 대기 좌석에 남아 있으면 드레인 누락")
                            .noneMatch(match.desertedSeats()::contains);

                    int seat = pending.get(rng.nextInt(pending.size()));
                    List<SkullKingAction> legal = engine.legalActions(state, seat);
                    SkullKingEngine.Result r = engine.applyAndDrain(state, match, seat,
                            legal.get(rng.nextInt(legal.size())));
                    state = r.newState();
                    all.addAll(r.events());
                    SkullKingInvariantChecker.check(state, match);
                }
                SkullKingEngine.Settlement settled =
                        engine.settleRound((SkullKingState.RoundEnd) state, match);
                match = settled.matchState();
                all.addAll(settled.events());
            }

            assertThat(match.roundNumber()).isEqualTo(SkullKingMatchState.TOTAL_ROUNDS + 1);
            assertThat(all.stream().filter(SkullKingEvent.RoundEnded.class::isInstance))
                    .hasSize(10);
            assertThat(match.winners())
                    .as("유령은 누적 점수와 무관하게 승자 후보에서 제외 (§13-⑳)")
                    .doesNotContain(2)
                    .isNotEmpty();
            SkullKingEvent.MatchEnded ended = all.stream()
                    .filter(SkullKingEvent.MatchEnded.class::isInstance)
                    .map(SkullKingEvent.MatchEnded.class::cast)
                    .findFirst().orElseThrow();
            assertThat(ended.finalScores())
                    .as("유령의 점수 궤적도 계속 기록된다 (§13-⑳)")
                    .containsKey(2);
        }
    }

    @Nested
    @DisplayName("최약수 정책 — timeoutAction 과 자동조종이 공유")
    class WeakestPolicy {

        private SkullKingState playingWithHand(List<SkullCard> hand) {
            List<PlayerState> players = List.of(
                    PlayerState.initial(0, hand).withBid(0),
                    PlayerState.initial(1, List.of(green(9))).withBid(0));
            return new SkullKingState.Playing(hand.size(), players, 0, TrickState.lead(0));
        }

        private final SkullKingEngine engine = engineFor(2);

        @Test
        void a_non_black_low_card_is_weaker_than_black() {
            SkullKingState state = playingWithHand(
                    List.of(SkullCard.of(SkullSuit.BLACK, 1), green(14)));

            assertThat(engine.timeoutAction(state, 0))
                    .isEqualTo(SkullKingAction.PlayCard.of(green(14)));
        }

        @Test
        void black_is_weaker_than_any_character() {
            SkullKingState state = playingWithHand(
                    List.of(SkullCard.mermaid(), SkullCard.of(SkullSuit.BLACK, 14)));

            assertThat(engine.timeoutAction(state, 0))
                    .isEqualTo(SkullKingAction.PlayCard.of(SkullCard.of(SkullSuit.BLACK, 14)));
        }

        @Test
        void the_character_order_is_mermaid_then_pirate_then_skull_king() {
            assertThat(engine.timeoutAction(
                    playingWithHand(List.of(SkullCard.skullKing(), SkullCard.pirate())), 0))
                    .isEqualTo(SkullKingAction.PlayCard.of(SkullCard.pirate()));
            assertThat(engine.timeoutAction(
                    playingWithHand(List.of(SkullCard.pirate(), SkullCard.mermaid())), 0))
                    .isEqualTo(SkullKingAction.PlayCard.of(SkullCard.mermaid()));
        }

        @Test
        void a_tigress_is_played_as_an_escape() {
            SkullKingState state = playingWithHand(
                    List.of(SkullCard.tigress(), green(1)));

            assertThat(engine.timeoutAction(state, 0))
                    .isEqualTo(SkullKingAction.PlayCard.tigress(
                            com.mirboard.domain.game.skullking.card.TigressMode.ESCAPE));
        }
    }
}
