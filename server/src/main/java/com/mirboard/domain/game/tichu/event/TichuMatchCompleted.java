package com.mirboard.domain.game.tichu.event;

import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import java.util.List;

/**
 * 한 매치 전체가 종료되었음을 알리는 ApplicationEvent. STOMP inflight 이벤트
 * ({@link TichuEvent.MatchEnded}) 와 달리 본 이벤트는 영속화 / 전적 갱신을 위해
 * 발행된다. 라운드별 점수 누적이 1000점에 도달하고 양팀 점수가 다를 때 발행.
 */
public record TichuMatchCompleted(
        String roomId,
        List<Long> playerIds,
        int cumulativeTeamAScore,
        int cumulativeTeamBScore,
        Team winningTeam,
        List<RoundScore> roundScores) {

    public TichuMatchCompleted {
        playerIds = List.copyOf(playerIds);
        roundScores = List.copyOf(roundScores);
    }
}
