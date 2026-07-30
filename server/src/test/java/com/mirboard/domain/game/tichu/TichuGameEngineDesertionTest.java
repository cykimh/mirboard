package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.infra.messaging.DomainEventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * D-98 — 탈주의 <b>게임 규칙</b>은 엔진이 답한다. 티츄는 2:2 고정이라 한 명이 빠지면
 * 속행 불가 → 상대팀 승리. 과거 이 판단은 {@code infra.ws.DesertionService} 안에 있었다
 * (그쪽 테스트는 이제 인프라 절차만 검증).
 */
class TichuGameEngineDesertionTest {

    private static final List<Long> PLAYERS = List.of(10L, 20L, 30L, 40L);

    private final TichuGameStateStore stateStore = mock(TichuGameStateStore.class);
    private final TichuMatchStateStore matchStateStore = mock(TichuMatchStateStore.class);
    private final TichuRoundStarter roundStarter = mock(TichuRoundStarter.class);
    private final DomainEventBus events = mock(DomainEventBus.class);

    private TichuGameEngine engineWithStake(int stake) {
        return new TichuGameEngine(
                new GameContext("r1", PLAYERS, 1000, stake, List.of()),
                stateStore, matchStateStore, roundStarter, events);
    }

    private void givenMatchInProgress() {
        when(matchStateStore.load("r1"))
                .thenReturn(Optional.of(TichuMatchState.initial(PLAYERS, 1000)));
    }

    @Test
    void seat0_deserter_makes_team_B_win_and_publishes_with_deserter() {
        givenMatchInProgress();
        List<GameEvent> outbound = new ArrayList<>();

        boolean processed = engineWithStake(0).desert(0, 10L, outbound);

        assertThat(processed).isTrue();
        TichuMatchCompleted published = capturePublished();
        assertThat(published.winningTeam()).isEqualTo(Team.B); // seat0 = Team A → 상대 B 승.
        assertThat(published.deserterUserId()).isEqualTo(10L);
        assertThat(published.playerIds()).isEqualTo(PLAYERS);

        assertThat(outbound).singleElement().isInstanceOf(TichuEvent.MatchEnded.class);
        TichuEvent.MatchEnded ended = (TichuEvent.MatchEnded) outbound.get(0);
        assertThat(ended.winningTeam()).isEqualTo(Team.B);
        assertThat(ended.mvpUserId()).isNull(); // 탈주 종료는 MVP 미산정.
    }

    @Test
    void seat1_deserter_makes_team_A_win() {
        givenMatchInProgress();
        List<GameEvent> outbound = new ArrayList<>();

        assertThat(engineWithStake(0).desert(1, 20L, outbound)).isTrue();

        assertThat(capturePublished().winningTeam()).isEqualTo(Team.A);
    }

    /** D-82 — 매치가 이미 끝난(리매치 대기) 방의 이탈은 탈주가 아니다. */
    @Test
    void already_over_match_is_not_a_desertion() {
        TichuMatchState over = TichuMatchState.initial(PLAYERS, 1000)
                .withRoundCompleted(new RoundScore(1000, 0, -1, false));
        assertThat(over.isMatchOver()).isTrue(); // 전제 확인.
        when(matchStateStore.load("r1")).thenReturn(Optional.of(over));
        List<GameEvent> outbound = new ArrayList<>();

        boolean processed = engineWithStake(0).desert(0, 10L, outbound);

        assertThat(processed).isFalse();
        verify(events, never()).publish(any());
        assertThat(outbound).isEmpty();
    }

    /** 누적 점수는 보존하고 승팀만 강제한다 — 탈주가 점수를 조작하지 않는다. */
    @Test
    void cumulative_scores_are_preserved() {
        when(matchStateStore.load("r1")).thenReturn(Optional.of(
                TichuMatchState.initial(PLAYERS, 1000)
                        .withRoundCompleted(new RoundScore(120, 80, -1, false))));

        assertThat(engineWithStake(0).desert(0, 10L, new ArrayList<>())).isTrue();

        TichuMatchCompleted published = capturePublished();
        assertThat(published.cumulativeTeamAScore()).isEqualTo(120);
        assertThat(published.cumulativeTeamBScore()).isEqualTo(80);
    }

    /** D-81 — 방 판돈이 정산 이벤트로 전달돼야 RoomChipService 가 칩을 옮길 수 있다. */
    @Test
    void room_stake_is_carried_to_settlement_event() {
        givenMatchInProgress();

        assertThat(engineWithStake(100).desert(0, 10L, new ArrayList<>())).isTrue();

        assertThat(capturePublished().stake()).isEqualTo(100);
    }

    private TichuMatchCompleted capturePublished() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TichuMatchCompleted.class);
        return (TichuMatchCompleted) captor.getValue();
    }
}
