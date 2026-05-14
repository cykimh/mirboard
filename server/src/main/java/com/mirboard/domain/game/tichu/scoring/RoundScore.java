package com.mirboard.domain.game.tichu.scoring;

import com.mirboard.domain.game.tichu.state.Team;

/**
 * 한 라운드의 정산 결과. 두 팀 점수와 메타(첫 완주자, 더블 빅토리 여부) 가 함께 들어
 * 있어 UI / 로그에서 즉시 표시할 수 있다.
 */
public record RoundScore(int teamAScore, int teamBScore, int firstFinisherSeat, boolean doubleVictory) {

    public int scoreOf(Team team) {
        return team == Team.A ? teamAScore : teamBScore;
    }
}
