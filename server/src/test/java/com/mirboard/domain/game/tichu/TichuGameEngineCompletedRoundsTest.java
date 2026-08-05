package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TableView;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.infra.messaging.DomainEventBus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * D-108 — 끝난 라운드 점수가 공개 뷰에 실리는지 고정한다. 이 값이 resync 응답을 타고
 * 나가므로, 여기서 끊기면 새로고침·재접속·두 번째 기기에서 라운드 내역이 빈다.
 *
 * <p>{@code TableView.completedRounds} 는 {@code roundScores}(현재 라운드의 팀별 점수)와
 * <b>다른 것</b>이다. 두 필드가 서로 오염되지 않는지도 같이 본다 — 이름이 비슷해 가장
 * 헷갈리기 쉬운 지점이다.
 */
class TichuGameEngineCompletedRoundsTest {

    private static final List<Long> PLAYERS = List.of(1L, 2L, 3L, 4L);

    private final TichuMatchStateStore matchStates = mock(TichuMatchStateStore.class);
    private final TichuGameEngine engine = new TichuGameEngine(
            new GameContext("r1", PLAYERS),
            mock(TichuGameStateStore.class),
            matchStates,
            mock(TichuRoundStarter.class),
            mock(DomainEventBus.class));

    private static TichuState.Dealing dealingState() {
        List<PlayerState> players = IntStream.range(0, 4)
                .mapToObj(seat -> PlayerState.initial(seat, List.of(Card.normal(Suit.JADE, 5))))
                .toList();
        return new TichuState.Dealing(players, 8, Set.of(), Map.of());
    }

    private TableView publicView() {
        return (TableView) engine.publicView(dealingState());
    }

    @Test
    void completed_rounds_are_exposed_in_order_with_every_field() {
        when(matchStates.load("r1")).thenReturn(Optional.of(new TichuMatchState(
                PLAYERS, 340, 60, 3,
                List.of(new RoundScore(300, 0, 0, true),
                        new RoundScore(40, 60, 2, false)),
                1000, null)));

        List<TableView.CompletedRound> rounds = publicView().completedRounds();

        assertThat(rounds).containsExactly(
                new TableView.CompletedRound(300, 0, 0, true),
                new TableView.CompletedRound(40, 60, 2, false));
    }

    @Test
    void completed_rounds_is_empty_before_any_round_finishes() {
        when(matchStates.load("r1")).thenReturn(Optional.empty());   // 매치 시작 직후.

        assertThat(publicView().completedRounds()).isEmpty();
    }

    /**
     * 이름이 닮은 두 필드가 서로 오염되지 않는지. {@code roundScores} 는 Dealing 단계라
     * 0/0 이어야 하고, {@code completedRounds} 는 끝난 라운드를 그대로 들고 있어야 한다.
     */
    @Test
    void completed_rounds_does_not_leak_into_current_round_scores() {
        when(matchStates.load("r1")).thenReturn(Optional.of(new TichuMatchState(
                PLAYERS, 300, 0, 2,
                List.of(new RoundScore(300, 0, 1, true)),
                1000, null)));

        TableView view = publicView();

        assertThat(view.completedRounds()).hasSize(1);
        assertThat(view.roundScores()).containsOnly(entry(Team.A, 0), entry(Team.B, 0));
        assertThat(view.matchScores()).containsEntry(Team.A, 300);
    }
}
