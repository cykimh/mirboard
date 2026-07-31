package com.mirboard.domain.game.skullking;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.event.SkullKingEvent;
import com.mirboard.domain.game.skullking.invariant.SkullKingInvariantChecker;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 무작위 합법수만으로 10라운드 매치를 완주시킨다. 매 액션 직후
 * {@link SkullKingInvariantChecker} 를 돌려 카드 보존·좌석 순서·예측 범위를 검사한다.
 *
 * <p>S5 의 봇 풀매치 IT 에 대응하는 순수 버전 — Docker 없이 돈다.
 */
class SkullKingMatchSimulationTest {

    /** 한 매치를 끝까지 돌린 결과. */
    private record Played(SkullKingMatchState finalMatch,
                          List<SkullKingEvent> events,
                          int actionCount) {
    }

    private static Played playMatch(int seatCount, long seed) {
        Random rng = new Random(seed);
        List<Long> playerIds = new ArrayList<>();
        for (long i = 0; i < seatCount; i++) {
            playerIds.add(500L + i);
        }
        SkullKingEngine engine = new SkullKingEngine(new GameContext("sim", playerIds));

        SkullKingMatchState match =
                SkullKingMatchState.initial(seatCount, rng.nextInt(seatCount));
        List<SkullKingEvent> allEvents = new ArrayList<>();
        int actions = 0;

        while (!match.isMatchOver()) {
            SkullKingEngine.Result start = engine.startRound(match, rng);
            SkullKingState state = start.newState();
            allEvents.addAll(start.events());
            SkullKingInvariantChecker.check(state);

            int guard = 0;
            while (!engine.isRoundOver(state)) {
                if (++guard > 5_000) {
                    throw new AssertionError("round did not terminate: " + state.phaseName());
                }
                List<Integer> pending = engine.pendingSeats(state);
                assertThat(pending)
                        .as("진행 중인데 대기 좌석이 없다 — 교착")
                        .isNotEmpty();

                int seat = pending.get(rng.nextInt(pending.size()));
                List<SkullKingAction> legal = engine.legalActions(state, seat);
                assertThat(legal)
                        .as("좌석 %d 이 대기 중인데 합법 액션이 없다", seat)
                        .isNotEmpty();

                SkullKingEngine.Result result =
                        engine.apply(state, seat, legal.get(rng.nextInt(legal.size())));
                state = result.newState();
                allEvents.addAll(result.events());
                actions++;

                SkullKingInvariantChecker.check(state);
            }

            SkullKingEngine.Settlement settled =
                    engine.settleRound((SkullKingState.RoundEnd) state, match);
            match = settled.matchState();
            allEvents.addAll(settled.events());
        }
        return new Played(match, allEvents, actions);
    }

    @ParameterizedTest(name = "{0}인 매치")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void a_full_ten_round_match_completes_for_every_supported_seat_count(int seatCount) {
        Played played = playMatch(seatCount, 20260731L + seatCount);

        assertThat(played.finalMatch().isMatchOver()).isTrue();
        assertThat(played.finalMatch().roundNumber())
                .isEqualTo(SkullKingMatchState.TOTAL_ROUNDS + 1);
        assertThat(played.finalMatch().cumulativeScores()).hasSize(seatCount);
        assertThat(played.events()).anyMatch(SkullKingEvent.MatchEnded.class::isInstance);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 2L, 3L, 17L, 42L, 9001L})
    void four_player_matches_survive_many_seeds(long seed) {
        Played played = playMatch(4, seed);

        assertThat(played.finalMatch().isMatchOver()).isTrue();
        assertThat(played.finalMatch().winners()).isNotEmpty();
    }

    @Test
    void exactly_one_match_ended_event_is_emitted_and_it_comes_last() {
        Played played = playMatch(4, 123L);

        List<SkullKingEvent> ended = played.events().stream()
                .filter(SkullKingEvent.MatchEnded.class::isInstance).toList();

        assertThat(ended).hasSize(1);
        assertThat(played.events().get(played.events().size() - 1)).isSameAs(ended.get(0));
    }

    @Test
    void ten_round_ended_events_are_emitted_in_order() {
        Played played = playMatch(5, 555L);

        List<Integer> rounds = played.events().stream()
                .filter(SkullKingEvent.RoundEnded.class::isInstance)
                .map(SkullKingEvent.RoundEnded.class::cast)
                .map(SkullKingEvent.RoundEnded::roundNumber)
                .toList();

        assertThat(rounds).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    /** §13-⑮ — 라운드마다 시작 좌석이 한 칸씩 옮겨간다. */
    @Test
    void the_start_seat_rotates_once_per_round() {
        SkullKingMatchState match = SkullKingMatchState.initial(4, 0);
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            seen.add(match.startSeat());
            match = match.withRoundScored(Map.of(0, 0, 1, 0, 2, 0, 3, 0), 4);
        }

        assertThat(seen).containsExactly(0, 1, 2, 3, 0, 1, 2, 3);
    }

    /** 라운드마다 정확히 handSize 개의 트릭이 나온다 (§4 — 8인 후반은 라운드 번호와 다르다). */
    @ParameterizedTest(name = "{0}인")
    @CsvSource({"2", "4", "8"})
    void each_round_produces_exactly_hand_size_tricks(int seatCount) {
        Random rng = new Random(77);
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < seatCount; i++) {
            ids.add(i);
        }
        SkullKingEngine engine = new SkullKingEngine(new GameContext("sim", ids));
        SkullKingMatchState match = SkullKingMatchState.initial(seatCount, 0);

        while (!match.isMatchOver()) {
            int expectedTricks = Dealer.handSize(match.roundNumber(), seatCount);
            SkullKingEngine.Result start = engine.startRound(match, rng);
            SkullKingState state = start.newState();

            int tricks = 0;
            while (!engine.isRoundOver(state)) {
                List<Integer> pending = engine.pendingSeats(state);
                int seat = pending.get(rng.nextInt(pending.size()));
                List<SkullKingAction> legal = engine.legalActions(state, seat);
                SkullKingEngine.Result result =
                        engine.apply(state, seat, legal.get(rng.nextInt(legal.size())));
                state = result.newState();
                tricks += (int) result.events().stream()
                        .filter(SkullKingEvent.TrickTaken.class::isInstance).count();
            }

            assertThat(tricks)
                    .as("%d인 라운드 %d", seatCount, match.roundNumber())
                    .isEqualTo(expectedTricks);

            match = engine.settleRound((SkullKingState.RoundEnd) state, match).matchState();
        }
    }

    /** 승수 합 = 트릭 수. 트릭이 사라지거나 두 번 세어지지 않는다. */
    @Test
    void tricks_won_across_seats_sum_to_the_trick_count_every_round() {
        Random rng = new Random(2024);
        List<Long> ids = List.of(0L, 1L, 2L, 3L, 4L, 5L);
        SkullKingEngine engine = new SkullKingEngine(new GameContext("sim", ids));
        SkullKingMatchState match = SkullKingMatchState.initial(6, 1);

        while (!match.isMatchOver()) {
            int handSize = Dealer.handSize(match.roundNumber(), 6);
            SkullKingState state = engine.startRound(match, rng).newState();

            while (!engine.isRoundOver(state)) {
                List<Integer> pending = engine.pendingSeats(state);
                int seat = pending.get(rng.nextInt(pending.size()));
                List<SkullKingAction> legal = engine.legalActions(state, seat);
                state = engine.apply(state, seat, legal.get(rng.nextInt(legal.size()))).newState();
            }

            int totalWon = state.players().stream()
                    .mapToInt(PlayerState::tricksWonCount).sum();
            assertThat(totalWon)
                    .as("라운드 %d 의 승수 합", match.roundNumber())
                    .isEqualTo(handSize);

            match = engine.settleRound((SkullKingState.RoundEnd) state, match).matchState();
        }
    }

    /** 적중 실패한 좌석에는 보너스가 한 푼도 붙지 않는다 (§11) — 매치 전체에 걸쳐 확인. */
    @Test
    void a_missed_bid_never_carries_a_bonus_anywhere_in_a_match() {
        Played played = playMatch(4, 31337L);

        played.events().stream()
                .filter(SkullKingEvent.RoundEnded.class::isInstance)
                .map(SkullKingEvent.RoundEnded.class::cast)
                .forEach(ev -> ev.scores().forEach((seat, score) -> {
                    if (!score.bidHit()) {
                        assertThat(score.bonus())
                                .as("라운드 %d 좌석 %d — 예측 실패인데 보너스", ev.roundNumber(), seat)
                                .isZero();
                    }
                }));
    }

    @Test
    void a_match_takes_a_plausible_number_of_actions() {
        Played played = playMatch(4, 8L);

        // 입찰 4×10=40 + 카드 (1+2+...+10)×4=220 → 260.
        assertThat(played.actionCount()).isEqualTo(260);
    }
}
