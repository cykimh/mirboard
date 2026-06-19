package com.mirboard.domain.game.tichu.event;

import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import java.util.List;

/**
 * 한 매치 전체가 종료되었음을 알리는 ApplicationEvent. STOMP inflight 이벤트
 * ({@link TichuEvent.MatchEnded}) 와 달리 본 이벤트는 영속화 / 전적 갱신을 위해
 * 발행된다. 라운드별 점수 누적이 1000점에 도달하고 양팀 점수가 다를 때 발행.
 *
 * <p>{@code deserterUserId}: Phase 19(#3, D-75) — 게임중 탈주로 강제 종료된
 * 경우 탈주자 userId, 정상 종료면 {@code null}. 비-null 이면
 * {@code MatchResultRecorder} 가 해당 유저 desert_count 를 추가 증분한다.
 *
 * <p>{@code stake}: D-81 — 방의 판돈(가상 칩). 0=내기 없음. {@code MatchResultRecorder}
 * 가 정산 시 승팀 +stake / 패팀 −stake 로 칩을 이동한다(봇 매치 제외).
 */
public record TichuMatchCompleted(
        String roomId,
        List<Long> playerIds,
        int cumulativeTeamAScore,
        int cumulativeTeamBScore,
        Team winningTeam,
        List<RoundScore> roundScores,
        Long deserterUserId,
        int stake) {

    public TichuMatchCompleted {
        playerIds = List.copyOf(playerIds);
        roundScores = List.copyOf(roundScores);
    }
}
