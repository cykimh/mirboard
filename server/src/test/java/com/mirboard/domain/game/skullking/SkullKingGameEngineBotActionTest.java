package com.mirboard.domain.game.skullking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.bot.SkullKingBotPolicy;
import com.mirboard.domain.game.skullking.bot.SkullKingBotView;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * D-119·D-230 — 어댑터 배선. 봇은 휴리스틱 정책을 타고, 정책이 실패해도(예외·null·비합법)
 * 최약수 {@code timeoutAction} 으로 떨어져 방이 멈추지 않는다. 타임아웃·유령 경로는
 * 그대로 최약수다 (D-104). 저장소가 필요 없는 경로만 보므로 Docker 없이 돈다.
 */
class SkullKingGameEngineBotActionTest {

    private static final GameContext CONTEXT =
            new GameContext("bot-adapter", List.of(10L, 11L, 12L, 13L));

    private final SkullKingMatchStateStore matchStore = mock(SkullKingMatchStateStore.class);
    private final SkullKingGameEngine engine =
            new SkullKingGameEngine(CONTEXT, null, matchStore, null, event -> { });
    private final SkullKingEngine rules = new SkullKingEngine(CONTEXT);

    private SkullKingState dealt(long seed) {
        return rules.startRound(new SkullKingMatchState(3, 0, java.util.Map.of()),
                new Random(seed)).newState();
    }

    /** 좌석 0 은 1, 나머지는 0 을 예측한 뒤의 플레이 국면 — 좌석 0 이 첫 리드. */
    private SkullKingState playingFrom(SkullKingState bidding) {
        SkullKingState state = bidding;
        for (int seat = 0; seat < CONTEXT.seatCount(); seat++) {
            state = rules.apply(state, seat, new SkullKingAction.PlaceBid(seat == 0 ? 1 : 0))
                    .newState();
        }
        return state;
    }

    @Test
    void bot_uses_heuristic_and_ignores_random() {
        for (SkullKingState state : List.of(dealt(7L), playingFrom(dealt(7L)))) {
            int seat = rules.pendingSeats(state).get(0);
            SkullKingAction expected = SkullKingBotPolicy.choose(
                    SkullKingBotView.of(state, seat), rules.legalActions(state, seat));

            for (long randomSeed : new long[] {1L, 2L, 3L}) {
                assertThat(engine.botAction(state, seat, new Random(randomSeed)))
                        .as("%s 좌석 %d · Random(%d)", state.phaseName(), seat, randomSeed)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void bot_view_carries_public_match_history() {
        // D-230 — 지난 라운드 예측(ROUND_ENDED 로 공개)과 탈주 좌석(SEAT_DESERTED)만 뷰에 실린다.
        Map<Integer, RoundScore> round1 = Map.of(
                0, new RoundScore(1, 1, 20, 0), 1, new RoundScore(0, 0, 10, 0),
                2, new RoundScore(0, 1, -10, 0), 3, new RoundScore(1, 0, -10, 0));
        SkullKingMatchState match = new SkullKingMatchState(2, 1,
                Map.of(0, 20, 1, 10, 2, -10, 3, -10), Set.of(2),
                List.of(new SkullKingMatchState.CompletedRound(1, round1)), 1);
        when(matchStore.load(any())).thenReturn(Optional.of(match));

        SkullKingState state = rules.startRound(match, new Random(5L)).newState();
        AtomicReference<SkullKingBotView> seen = new AtomicReference<>();
        engine.chooseBotAction(state, 0, (view, legal) -> {
            seen.set(view);
            return legal.get(0);
        });

        assertThat(seen.get().pastBids()).containsExactly(List.of(1, 0, 0, 1));
        assertThat(seen.get().deserted()).containsExactly(2);
        assertThat(seen.get().bids()).as("입찰 중 — 이번 라운드 예측은 비공개")
                .containsOnly(PlayerState.NO_BID);
    }

    @Test
    void no_action_for_out_of_range_seat_or_round_end() {
        SkullKingState bidding = dealt(11L);
        assertThat(engine.botAction(bidding, -1, new Random(1))).isNull();
        assertThat(engine.botAction(bidding, CONTEXT.seatCount(), new Random(1))).isNull();

        SkullKingState roundEnd = new SkullKingState.RoundEnd(1, List.of(
                new PlayerState(0, List.of(), 0, List.of()),
                new PlayerState(1, List.of(), 0, List.of()),
                new PlayerState(2, List.of(), 0, List.of()),
                new PlayerState(3, List.of(), 0, List.of())), 0, java.util.Map.of());
        assertThat(engine.botAction(roundEnd, 0, new Random(1))).isNull();
    }

    @Test
    void policy_failure_falls_back_to_timeout_action() {
        for (SkullKingState state : List.of(dealt(13L), playingFrom(dealt(13L)))) {
            int seat = rules.pendingSeats(state).get(0);
            GameAction weakest = engine.timeoutAction(state, seat);

            assertThat(engine.chooseBotAction(state, seat, (view, legal) -> {
                throw new IllegalStateException("boom");
            })).as("정책 예외").isEqualTo(weakest);
            assertThat(engine.chooseBotAction(state, seat, (view, legal) -> null))
                    .as("정책 null").isEqualTo(weakest);
            assertThat(engine.chooseBotAction(state, seat,
                    (view, legal) -> SkullKingAction.PlayCard.of(SkullCard.tigress())))
                    .as("합법수 밖 액션").isEqualTo(weakest);
        }
    }

    @Test
    void timeout_keeps_weakest_policy() {
        SkullKingState.Bidding strongHand = new SkullKingState.Bidding(3, List.of(
                PlayerState.initial(0, List.of(
                        SkullCard.skullKing(), SkullCard.pirate(), SkullCard.pirate())),
                PlayerState.initial(1, List.of()),
                PlayerState.initial(2, List.of()),
                PlayerState.initial(3, List.of())), 0);

        GameAction timeout = engine.timeoutAction(strongHand, 0);
        assertThat(timeout)
                .as("타임아웃·유령 대리는 여전히 0 예측 (D-104 최약수)")
                .isEqualTo(rules.timeoutAction(strongHand, 0))
                .isEqualTo(new SkullKingAction.PlaceBid(0));
        assertThat(engine.botAction(strongHand, 0, new Random(1)))
                .as("봇은 1급 참가자 — 같은 손패로 3 을 부른다")
                .isEqualTo(new SkullKingAction.PlaceBid(3))
                .isNotEqualTo(timeout);
    }
}
