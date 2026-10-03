package com.mirboard.domain.game.scoring;

import java.util.List;
import java.util.Map;

/**
 * Phase 8D — 2 vs 2 팀 게임용 ELO 계산기 (D-40 정책).
 *
 * <p>팀 평균 rating 으로 기대 승률 산출 → 같은 팀 두 명이 동일한 +/- delta. 개별
 * rating 으로 처리하면 같은 팀이 승리해도 한 명은 점수가 떨어지는 모순이 생기는데,
 * 팀 평균 방식은 그 모순을 피하면서 표준 ELO 식을 그대로 쓸 수 있다.
 *
 * <ul>
 *   <li>{@code EA = 1 / (1 + 10^((avgB - avgA)/400))}</li>
 *   <li>{@code delta = K * (S - EA)}  (S=1 승, 0 패)</li>
 *   <li>K-factor: 30게임 미만 신규는 40, 그 외 32</li>
 * </ul>
 */
public final class EloCalculator {

    public static final int DEFAULT_K = 32;
    public static final int NEW_PLAYER_K = 40;
    public static final int NEW_PLAYER_THRESHOLD = 30;

    private EloCalculator() {}

    public record PlayerInput(long userId, int currentRating, int gamesPlayed) {
    }

    /**
     * 매치 결과 → 각 플레이어의 newRating 맵 반환. 입력 순서/사이즈에 의존하지
     * 않음 (팀별 List 전달).
     *
     * @param teamA   승팀 또는 패팀 (winnerIsTeamA 로 결정)
     * @param teamB   반대팀
     * @param winnerIsTeamA  true 면 teamA 승, false 면 teamB 승
     * @return userId → newRating 맵 (teamA + teamB 모두 포함)
     */
    public static Map<Long, Integer> applyMatch(List<PlayerInput> teamA,
                                                 List<PlayerInput> teamB,
                                                 boolean winnerIsTeamA) {
        if (teamA.isEmpty() || teamB.isEmpty()) {
            throw new IllegalArgumentException("Both teams must have at least one player");
        }
        double avgA = teamA.stream().mapToInt(PlayerInput::currentRating).average().orElseThrow();
        double avgB = teamB.stream().mapToInt(PlayerInput::currentRating).average().orElseThrow();

        // 기대 승률.
        double expectedA = 1.0 / (1.0 + Math.pow(10.0, (avgB - avgA) / 400.0));
        double expectedB = 1.0 - expectedA;

        double scoreA = winnerIsTeamA ? 1.0 : 0.0;
        double scoreB = 1.0 - scoreA;

        java.util.HashMap<Long, Integer> result = new java.util.HashMap<>();
        for (PlayerInput p : teamA) {
            int k = kFactor(p.gamesPlayed());
            int delta = (int) Math.round(k * (scoreA - expectedA));
            result.put(p.userId(), p.currentRating() + delta);
        }
        for (PlayerInput p : teamB) {
            int k = kFactor(p.gamesPlayed());
            int delta = (int) Math.round(k * (scoreB - expectedB));
            result.put(p.userId(), p.currentRating() + delta);
        }
        return java.util.Map.copyOf(result);
    }

    /**
     * D-115 — 개인전 한 명의 최종 성적.
     *
     * @param deserted 탈주 좌석. 점수와 무관하게 최하위로 취급한다(탈주끼리는 동률).
     */
    public record Placement(PlayerInput player, int score, boolean deserted) {
    }

    /**
     * D-115 — 개인전(2~8인) 쌍대 ELO. 모든 쌍 (i, j) 를 한 판으로 보고
     * {@code S_ij}(점수가 높으면 1, 같으면 0.5, 낮으면 0)와 개인 rating 기대 승률의 차를
     * {@code K_i / (n−1)} 로 합산한다. 2인이면 일반 ELO 와 같고, 같은 K 면 증감 합이 보존된다.
     *
     * @return userId → newRating 맵 (전원 포함)
     */
    public static Map<Long, Integer> applyFreeForAll(List<Placement> placements) {
        int n = placements.size();
        if (n < 2) {
            throw new IllegalArgumentException("Free-for-all needs at least two players");
        }
        java.util.HashMap<Long, Integer> result = new java.util.HashMap<>();
        for (Placement me : placements) {
            double sum = 0.0;
            for (Placement other : placements) {
                if (other == me) {
                    continue;
                }
                double expected = 1.0 / (1.0 + Math.pow(10.0,
                        (other.player().currentRating() - me.player().currentRating()) / 400.0));
                sum += outcome(me, other) - expected;
            }
            int k = kFactor(me.player().gamesPlayed());
            int delta = (int) Math.round(k * sum / (n - 1));
            result.put(me.player().userId(), me.player().currentRating() + delta);
        }
        return java.util.Map.copyOf(result);
    }

    /** me 기준 한 쌍의 결과: 1 승, 0.5 무, 0 패. 탈주는 비탈주보다 항상 아래. */
    private static double outcome(Placement me, Placement other) {
        if (me.deserted() != other.deserted()) {
            return me.deserted() ? 0.0 : 1.0;
        }
        if (me.deserted()) {
            return 0.5;
        }
        return me.score() > other.score() ? 1.0 : me.score() == other.score() ? 0.5 : 0.0;
    }

    public static int kFactor(int gamesPlayed) {
        return gamesPlayed < NEW_PLAYER_THRESHOLD ? NEW_PLAYER_K : DEFAULT_K;
    }
}
