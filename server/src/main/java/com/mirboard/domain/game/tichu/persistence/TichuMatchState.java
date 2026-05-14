package com.mirboard.domain.game.tichu.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 한 매치 (여러 라운드 합산) 의 누적 상태. 라운드 단위 {@link
 * com.mirboard.domain.game.tichu.state.TichuState} 와 별도로 영속화한다.
 *
 * @param playerIds       매치 참가자 (좌석 0..3 순서)
 * @param cumulativeA     Team A 누적 점수
 * @param cumulativeB     Team B 누적 점수
 * @param roundNumber     현재 진행 중 또는 막 끝난 라운드 번호 (1부터)
 * @param roundScores     라운드별 점수 (직렬화/감사용)
 */
public record TichuMatchState(
        List<Long> playerIds,
        int cumulativeA,
        int cumulativeB,
        int roundNumber,
        List<RoundScore> roundScores) {

    public TichuMatchState {
        playerIds = List.copyOf(playerIds);
        roundScores = List.copyOf(roundScores);
    }

    public static TichuMatchState initial(List<Long> playerIds) {
        return new TichuMatchState(playerIds, 0, 0, 1, List.of());
    }

    public TichuMatchState withRoundCompleted(RoundScore score) {
        List<RoundScore> next = new ArrayList<>(roundScores);
        next.add(score);
        return new TichuMatchState(
                playerIds,
                cumulativeA + score.teamAScore(),
                cumulativeB + score.teamBScore(),
                roundNumber + 1,
                next);
    }

    public Map<Team, Integer> scoresByTeam() {
        Map<Team, Integer> m = new EnumMap<>(Team.class);
        m.put(Team.A, cumulativeA);
        m.put(Team.B, cumulativeB);
        return m;
    }

    /** 매치 종료 조건: 한 팀 ≥1000 이고 양팀 점수가 다르다. */
    @JsonIgnore
    public boolean isMatchOver() {
        if (cumulativeA < 1000 && cumulativeB < 1000) return false;
        return cumulativeA != cumulativeB;
    }

    /** 매치 종료 시 승리 팀. 종료 아니면 {@link IllegalStateException}. */
    public Team winningTeam() {
        if (!isMatchOver()) throw new IllegalStateException("match not over yet");
        return cumulativeA > cumulativeB ? Team.A : Team.B;
    }
}
