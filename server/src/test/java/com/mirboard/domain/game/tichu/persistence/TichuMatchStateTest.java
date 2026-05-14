package com.mirboard.domain.game.tichu.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import java.util.List;
import org.junit.jupiter.api.Test;

class TichuMatchStateTest {

    private static final List<Long> IDS = List.of(1L, 2L, 3L, 4L);

    @Test
    void initial_state_starts_at_round_1_with_zero_scores() {
        TichuMatchState s = TichuMatchState.initial(IDS);
        assertThat(s.roundNumber()).isEqualTo(1);
        assertThat(s.cumulativeA()).isZero();
        assertThat(s.cumulativeB()).isZero();
        assertThat(s.isMatchOver()).isFalse();
    }

    @Test
    void withRoundCompleted_accumulates_and_bumps_round() {
        TichuMatchState s = TichuMatchState.initial(IDS)
                .withRoundCompleted(new RoundScore(120, 80, 0, false));
        assertThat(s.roundNumber()).isEqualTo(2);
        assertThat(s.cumulativeA()).isEqualTo(120);
        assertThat(s.cumulativeB()).isEqualTo(80);
        assertThat(s.roundScores()).hasSize(1);
    }

    @Test
    void match_over_when_one_team_reaches_1000_and_scores_differ() {
        TichuMatchState s = TichuMatchState.initial(IDS)
                .withRoundCompleted(new RoundScore(600, 200, 0, false))
                .withRoundCompleted(new RoundScore(450, 100, 0, false));
        assertThat(s.cumulativeA()).isEqualTo(1050);
        assertThat(s.cumulativeB()).isEqualTo(300);
        assertThat(s.isMatchOver()).isTrue();
        assertThat(s.winningTeam()).isEqualTo(Team.A);
    }

    @Test
    void match_not_over_when_both_teams_tie_at_1000() {
        TichuMatchState s = TichuMatchState.initial(IDS)
                .withRoundCompleted(new RoundScore(1000, 1000, 0, false));
        assertThat(s.cumulativeA()).isEqualTo(1000);
        assertThat(s.cumulativeB()).isEqualTo(1000);
        assertThat(s.isMatchOver()).isFalse();
    }

    @Test
    void winningTeam_throws_when_match_not_over() {
        TichuMatchState s = TichuMatchState.initial(IDS)
                .withRoundCompleted(new RoundScore(200, 100, 0, false));
        assertThatThrownBy(s::winningTeam).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void match_over_when_only_team_b_reaches_1000() {
        TichuMatchState s = TichuMatchState.initial(IDS)
                .withRoundCompleted(new RoundScore(200, 600, 0, false))
                .withRoundCompleted(new RoundScore(150, 500, 0, false));
        assertThat(s.cumulativeB()).isEqualTo(1100);
        assertThat(s.cumulativeA()).isEqualTo(350);
        assertThat(s.isMatchOver()).isTrue();
        assertThat(s.winningTeam()).isEqualTo(Team.B);
    }
}
