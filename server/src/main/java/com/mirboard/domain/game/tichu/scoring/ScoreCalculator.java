package com.mirboard.domain.game.tichu.scoring;

import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import java.util.List;
import java.util.Optional;

/**
 * 라운드 종료 시점에 두 팀의 점수를 계산한다.
 *
 * <p>종료 경로 두 가지:
 * <ol>
 *   <li><b>더블 빅토리</b>: 한 팀의 두 명이 1·2등을 차지하면 즉시 종료. 트릭 합산은
 *       생략하고 해당 팀에 +200. 티츄 선언 보너스는 별도로 합산.</li>
 *   <li><b>정상 종료</b>: 4명 중 3명이 카드를 다 소진한 시점. 패자(4등) 의 트릭은
 *       1등 팀에, 패자 손에 남은 카드 점수는 상대 팀에 가산. 그 외 트릭은 각자 팀에
 *       귀속. 티츄 선언 보너스 추가.</li>
 * </ol>
 */
public final class ScoreCalculator {

    private ScoreCalculator() {
    }

    public static RoundScore compute(List<PlayerState> players) {
        if (players.size() != 4) {
            throw new IllegalArgumentException("Tichu round needs exactly 4 players, got: " + players.size());
        }

        int firstFinisherSeat = firstFinisherSeat(players);
        if (firstFinisherSeat < 0) {
            throw new IllegalStateException("Round cannot be scored before someone finishes");
        }

        Optional<Team> doubleVictoryWinner = detectDoubleVictory(players);
        int teamA = declarationBonus(players, Team.A, firstFinisherSeat);
        int teamB = declarationBonus(players, Team.B, firstFinisherSeat);

        if (doubleVictoryWinner.isPresent()) {
            Team winner = doubleVictoryWinner.get();
            teamA += (winner == Team.A) ? CardPoints.DOUBLE_VICTORY_BONUS : 0;
            teamB += (winner == Team.B) ? CardPoints.DOUBLE_VICTORY_BONUS : 0;
            return new RoundScore(teamA, teamB, firstFinisherSeat, true);
        }

        // Normal end: exactly 3 finished, 1 left.
        long finishedCount = players.stream().filter(PlayerState::isFinished).count();
        if (finishedCount != 3) {
            throw new IllegalStateException(
                    "Normal scoring needs exactly 3 finished players, got: " + finishedCount);
        }

        PlayerState loser = players.stream()
                .filter(p -> !p.isFinished())
                .findFirst()
                .orElseThrow();
        PlayerState firstFinisher = players.stream()
                .filter(p -> p.finishedOrder() == 1)
                .findFirst()
                .orElseThrow();

        // Non-loser tricks belong to each player's team.
        for (PlayerState p : players) {
            if (p == loser) continue;
            int trickPoints = CardPoints.sum(p.tricksWon());
            if (p.team() == Team.A) teamA += trickPoints;
            else teamB += trickPoints;
        }

        // Loser's tricks → first finisher's team.
        int loserTrickPoints = CardPoints.sum(loser.tricksWon());
        if (firstFinisher.team() == Team.A) teamA += loserTrickPoints;
        else teamB += loserTrickPoints;

        // Loser's remaining hand card points → opposing team.
        int loserHandPoints = CardPoints.sum(loser.hand());
        if (loser.team() == Team.A) teamB += loserHandPoints;
        else teamA += loserHandPoints;

        return new RoundScore(teamA, teamB, firstFinisherSeat, false);
    }

    private static Optional<Team> detectDoubleVictory(List<PlayerState> players) {
        List<PlayerState> top2 = players.stream()
                .filter(PlayerState::isFinished)
                .filter(p -> p.finishedOrder() <= 2)
                .toList();
        if (top2.size() != 2) return Optional.empty();
        Team t0 = top2.get(0).team();
        Team t1 = top2.get(1).team();
        if (t0 != t1) return Optional.empty();
        // Also require nobody finished in 3rd yet (i.e., exactly 2 finishers).
        long allFinished = players.stream().filter(PlayerState::isFinished).count();
        return allFinished == 2 ? Optional.of(t0) : Optional.empty();
    }

    private static int firstFinisherSeat(List<PlayerState> players) {
        return players.stream()
                .filter(p -> p.finishedOrder() == 1)
                .mapToInt(PlayerState::seat)
                .findFirst()
                .orElse(-1);
    }

    private static int declarationBonus(List<PlayerState> players, Team team, int firstFinisherSeat) {
        int total = 0;
        for (PlayerState p : players) {
            if (p.team() != team) continue;
            int amount = switch (p.declaration()) {
                case NONE -> 0;
                case TICHU -> CardPoints.TICHU_BONUS;
                case GRAND_TICHU -> CardPoints.GRAND_TICHU_BONUS;
            };
            if (amount == 0) continue;
            boolean success = p.seat() == firstFinisherSeat;
            total += success ? amount : -amount;
        }
        return total;
    }
}
