package com.mirboard.domain.game.tichu.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MvpCalculatorTest {

    private static final List<Long> IDS = List.of(10L, 11L, 12L, 13L); // seat 0..3

    @Test
    void selects_highest_weighted_on_winning_team() {
        List<SeatContribution> totals = List.of(
                new SeatContribution(0, 200, 100, 2, 6), // Team A
                new SeatContribution(1, 50, 0, 0, 1),    // Team B
                new SeatContribution(2, 80, 0, 1, 3),    // Team A
                new SeatContribution(3, 40, 0, 0, 0));   // Team B

        var mvp = MvpCalculator.select(totals, IDS, Team.A, List.of());

        assertThat(mvp).isPresent();
        assertThat(mvp.get().userId()).isEqualTo(10L);
        assertThat(mvp.get().seat()).isEqualTo(0);
        assertThat(mvp.get().stat()).contains("트릭 200").contains("티츄 +100");
    }

    @Test
    void prefers_human_over_bot_on_winning_team() {
        List<SeatContribution> totals = List.of(
                new SeatContribution(0, 300, 200, 3, 9), // Team A, bot (점수 높음)
                new SeatContribution(2, 60, 0, 0, 2),    // Team A, 사람
                new SeatContribution(1, 0, 0, 0, 0),
                new SeatContribution(3, 0, 0, 0, 0));

        var mvp = MvpCalculator.select(totals, IDS, Team.A, List.of(0)); // seat0 = 봇

        assertThat(mvp).isPresent();
        assertThat(mvp.get().seat()).isEqualTo(2); // 점수 낮아도 사람 우선
    }

    @Test
    void falls_back_to_bot_when_winning_team_all_bots() {
        List<SeatContribution> totals = List.of(
                new SeatContribution(0, 100, 0, 1, 3),
                new SeatContribution(2, 50, 0, 0, 1),
                new SeatContribution(1, 0, 0, 0, 0),
                new SeatContribution(3, 0, 0, 0, 0));

        var mvp = MvpCalculator.select(totals, IDS, Team.A, List.of(0, 2)); // A 좌석 둘 다 봇

        assertThat(mvp).isPresent();
        assertThat(mvp.get().seat()).isEqualTo(0);
    }

    @Test
    void roundContributions_capture_declaration_and_finish() {
        List<PlayerState> players = List.of(
                new PlayerState(0, List.of(), TichuDeclaration.TICHU, 1, List.of()),  // 성공
                new PlayerState(1, List.of(), TichuDeclaration.TICHU, -1, List.of()), // 실패
                new PlayerState(2, List.of(), TichuDeclaration.NONE, 2, List.of()),
                new PlayerState(3, List.of(), TichuDeclaration.NONE, 3, List.of()));

        var contribs = MvpCalculator.roundContributions(players);

        assertThat(contribs.get(0).tichuBonus()).isEqualTo(100);
        assertThat(contribs.get(0).firstFinishes()).isEqualTo(1);
        assertThat(contribs.get(0).orderPoints()).isEqualTo(3);
        assertThat(contribs.get(1).tichuBonus()).isEqualTo(-100);
        assertThat(contribs.get(2).orderPoints()).isEqualTo(2);
    }
}
