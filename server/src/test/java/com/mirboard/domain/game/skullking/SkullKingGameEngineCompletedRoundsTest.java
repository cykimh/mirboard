package com.mirboard.domain.game.skullking;

import static com.mirboard.domain.game.skullking.state.MatchStateFixtures.scored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.skullking.event.SkullKingEvent;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.SkullKingStateMapper;
import com.mirboard.domain.game.skullking.state.SkullKingStateMapper.CompletedRoundView;
import com.mirboard.domain.game.skullking.state.SkullKingStateMapper.RoundScoreView;
import com.mirboard.domain.game.skullking.state.SkullKingStateMapper.TableView;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-120 — 끝난 라운드 기록·매치 결과가 공개 뷰에 실리는지 고정한다(티츄 D-108
 * {@code TichuGameEngineCompletedRoundsTest} 대응). 이 값이 resync 응답을 타고 나가므로
 * 여기서 끊기면 새로고침·재접속 뒤 점수표와 종료 패널이 빈다.
 *
 * <p>함께 고정하는 것: {@code completedRounds} 는 <b>정산이 끝난 라운드만</b> 담는다 — 다음
 * 라운드 Bidding 뷰에서 공개 전 예측값이 새지 않아야 한다(§5). 그리고 "정산 후 다음 라운드
 * 저장 전에 멈춘 방"의 복구 분기가 이중 정산하지 않는다.
 */
class SkullKingGameEngineCompletedRoundsTest {

    private static final List<Long> PLAYERS = List.of(1L, 2L, 3L);

    private final SkullKingStateStore states = mock(SkullKingStateStore.class);
    private final SkullKingMatchStateStore matchStates = mock(SkullKingMatchStateStore.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SkullKingGameEngine engine = new SkullKingGameEngine(
            new GameContext("r1", PLAYERS), states, matchStates, new SecureRandom(), publisher);

    private static final Map<Integer, RoundScore> R1 = Map.of(
            0, new RoundScore(1, 1, 20, 10),
            1, new RoundScore(0, 1, -10, 0),
            2, new RoundScore(0, 0, 10, 0));
    private static final Map<Integer, RoundScore> R2 = Map.of(
            0, new RoundScore(2, 1, -10, 0),
            1, new RoundScore(1, 1, 20, 0),
            2, new RoundScore(0, 0, 20, 0));

    /** 라운드 1·2 를 정산하고 라운드 3 을 기다리는 매치. */
    private static SkullKingMatchState afterTwoRounds() {
        return SkullKingMatchState.initial(3, 0)
                .withRoundCompleted(1, R1, 3)
                .withRoundCompleted(2, R2, 3);
    }

    /** 라운드 N 입찰 중 — 좌석 0 만 제출(값 2). 공개 전이다. */
    private static SkullKingState.Bidding biddingWithOneBid(int round) {
        return new SkullKingState.Bidding(round, List.of(
                PlayerState.initial(0, List.of()).withBid(2),
                PlayerState.initial(1, List.of()),
                PlayerState.initial(2, List.of())), 0);
    }

    private TableView publicView(SkullKingState state) {
        return (TableView) engine.publicView(state);
    }

    @Nested
    class CompletedRounds {

        @Test
        void completed_rounds_are_exposed_in_order_with_totals() {
            when(matchStates.load("r1")).thenReturn(Optional.of(afterTwoRounds()));

            List<CompletedRoundView> rounds = publicView(biddingWithOneBid(3)).completedRounds();

            assertThat(rounds).extracting(CompletedRoundView::roundNumber).containsExactly(1, 2);
            assertThat(rounds.get(0).scores()).containsEntry(0, new RoundScoreView(1, 1, 20, 10, 30))
                    .containsEntry(1, new RoundScoreView(0, 1, -10, 0, -10))
                    .containsEntry(2, new RoundScoreView(0, 0, 10, 0, 10));
            assertThat(rounds.get(1).scores().get(1).total()).isEqualTo(20);
        }

        @Test
        void completed_rounds_is_empty_before_any_round_finishes() {
            when(matchStates.load("r1")).thenReturn(Optional.empty());   // 매치 시작 직후.

            assertThat(publicView(biddingWithOneBid(1)).completedRounds()).isEmpty();
        }

        /**
         * 다음 라운드 Bidding 에서 기록은 지난 라운드까지만 — 진행 중 라운드의 공개 전
         * 예측(좌석 0 의 2)은 seats 에도 기록에도 roundScores 에도 없다.
         */
        @Test
        void the_current_round_never_leaks_into_history_or_round_scores() {
            when(matchStates.load("r1")).thenReturn(Optional.of(afterTwoRounds()));

            TableView view = publicView(biddingWithOneBid(3));

            assertThat(view.seats()).allMatch(seat -> seat.bid() == null);
            assertThat(view.seats().get(0).hasBid()).isTrue();
            assertThat(view.roundScores()).isEmpty();
            assertThat(view.completedRounds()).noneMatch(r -> r.roundNumber() == 3);
        }
    }

    @Nested
    class MatchResult {

        @Test
        void match_result_is_null_while_the_match_is_running() {
            when(matchStates.load("r1")).thenReturn(Optional.of(afterTwoRounds()));

            assertThat(publicView(biddingWithOneBid(3)).matchResult()).isNull();
        }

        @Test
        void a_completed_match_exposes_winners_final_scores_and_ten_rounds() {
            SkullKingMatchState match = SkullKingMatchState.initial(3, 0);
            for (int i = 0; i < SkullKingMatchState.TOTAL_ROUNDS; i++) {
                match = scored(match, Map.of(0, 10, 1, 20, 2, 20), 3);
            }
            when(matchStates.load("r1")).thenReturn(Optional.of(match));

            SkullKingStateMapper.MatchResultView result =
                    publicView(roundEnd(10)).matchResult();

            assertThat(result).isNotNull();
            assertThat(result.winners()).containsExactly(1, 2);
            assertThat(result.finalScores()).containsEntry(0, 100).containsEntry(1, 200)
                    .containsEntry(2, 200);
            assertThat(result.roundsPlayed()).isEqualTo(10);
        }

        /** 조기 종료 — roundsPlayed 가 엔진이 낸 MatchEnded.roundsPlayed 와 같아야 한다. */
        @Test
        void an_early_ended_match_reports_the_same_rounds_played_as_the_event() {
            SkullKingEngine rules = new SkullKingEngine(new GameContext("r1", PLAYERS));
            SkullKingMatchState match = afterTwoRounds();
            SkullKingState state = rules.startRound(match, new Random(7)).newState();
            SkullKingEngine.Desertion first = rules.desert(state, match, 0, Set.of(0, 1, 2));
            SkullKingEngine.Desertion second = rules.desert(
                    first.newState(), first.matchState(), 1, Set.of(0, 1, 2));
            SkullKingEvent.MatchEnded ended = second.events().stream()
                    .filter(SkullKingEvent.MatchEnded.class::isInstance)
                    .map(SkullKingEvent.MatchEnded.class::cast)
                    .findFirst().orElseThrow();
            when(matchStates.load("r1")).thenReturn(Optional.of(second.matchState()));

            SkullKingStateMapper.MatchResultView result =
                    publicView(second.newState()).matchResult();

            assertThat(result.roundsPlayed()).isEqualTo(ended.roundsPlayed()).isEqualTo(2);
            assertThat(result.winners()).isEqualTo(ended.winners());
            assertThat(result.finalScores()).isEqualTo(ended.finalScores());
        }

        /** 기록 없는 구 JSON 매치 — 라운드 수를 상태로 역산한다(MatchEnded 와 같은 값). */
        @Test
        void rounds_played_falls_back_to_the_state_without_history() {
            when(matchStates.load("r1")).thenReturn(Optional.of(
                    new SkullKingMatchState(11, 0, Map.of(0, 10, 1, 0, 2, 0))));

            assertThat(publicView(roundEnd(10)).matchResult().roundsPlayed())
                    .as("RoundEnd 면 그 라운드까지 완주")
                    .isEqualTo(10);
            assertThat(publicView(biddingWithOneBid(4)).matchResult().roundsPlayed())
                    .as("진행 중 라운드에서 끝났으면 그 앞까지")
                    .isEqualTo(3);
        }
    }

    /**
     * 복구 분기 — 정산(매치 상태 저장)까지 하고 다음 라운드 상태를 저장하기 전에 멈춘 방.
     * 저장된 상태는 RoundEnd(N), 매치는 이미 N+1 이다. 탈주 CONTINUED 가 이 상태로
     * {@code advance} 를 다시 부르면, 이중 정산 대신 다음 라운드만 시작해야 한다.
     */
    @Nested
    class Recovery {

        @Test
        void an_already_settled_round_starts_the_next_round_without_settling_again() {
            SkullKingMatchState match = afterTwoRounds();   // 라운드 2 정산 완료, 3 대기.
            when(matchStates.load("r1")).thenReturn(Optional.of(match));
            List<GameEvent> outbound = new ArrayList<>();

            GameEngine.Advance advance = engine.advance(roundEnd(2), outbound);

            assertThat(advance).isEqualTo(GameEngine.Advance.NONE);
            verify(matchStates, never()).save(any(), any());
            verify(publisher, never()).publishEvent(any(Object.class));
            verify(states).save(eq("r1"), any(SkullKingState.Bidding.class));
            assertThat(outbound)
                    .noneMatch(SkullKingEvent.RoundEnded.class::isInstance)
                    .noneMatch(SkullKingEvent.MatchEnded.class::isInstance)
                    .anyMatch(e -> e instanceof SkullKingEvent.BiddingStarted bs
                            && bs.roundNumber() == 3);
        }

        @Test
        void an_already_settled_final_round_does_nothing() {
            SkullKingMatchState match = SkullKingMatchState.initial(3, 0);
            for (int i = 0; i < SkullKingMatchState.TOTAL_ROUNDS; i++) {
                match = scored(match, Map.of(0, 0, 1, 0, 2, 0), 3);
            }
            when(matchStates.load("r1")).thenReturn(Optional.of(match));
            List<GameEvent> outbound = new ArrayList<>();

            GameEngine.Advance advance = engine.advance(roundEnd(10), outbound);

            assertThat(advance).isEqualTo(GameEngine.Advance.NONE);
            assertThat(outbound).isEmpty();
            verify(matchStates, never()).save(any(), any());
            verify(states, never()).save(any(), any());
            verify(publisher, never()).publishEvent(any(Object.class));
        }

        /** 정상 경로는 그대로 — 정산하고 기록을 남긴다. */
        @Test
        void an_unsettled_round_is_settled_once_and_recorded() {
            when(matchStates.load("r1")).thenReturn(Optional.of(
                    SkullKingMatchState.initial(3, 0).withRoundCompleted(1, R1, 3)));
            List<GameEvent> outbound = new ArrayList<>();

            GameEngine.Advance advance = engine.advance(roundEnd(2), outbound);

            assertThat(advance).isEqualTo(new GameEngine.Advance(true, false));
            verify(matchStates).save(eq("r1"), org.mockito.ArgumentMatchers.argThat(
                    (SkullKingMatchState m) -> m.completedRounds().size() == 2
                            && m.roundNumber() == 3));
            assertThat(outbound).anyMatch(SkullKingEvent.RoundEnded.class::isInstance);
        }
    }

    /** 라운드 N 종료 상태 — 점수는 R2 를 재사용한다(값 자체는 이 테스트의 관심이 아니다). */
    private static SkullKingState.RoundEnd roundEnd(int round) {
        return new SkullKingState.RoundEnd(round, List.of(
                PlayerState.initial(0, List.of()),
                PlayerState.initial(1, List.of()),
                PlayerState.initial(2, List.of())), 0, R2);
    }
}
