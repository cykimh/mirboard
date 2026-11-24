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
 *   <li>D-297 — 깎일 때는 절반만({@link #LOSS_FACTOR}). 친구끼리 자주 지는 사람도 계급이 오르게 — 증감 합이 0 이 아니라 조금씩
 *       오른다(레이팅 인플레이션을 받아들인다). 탈주도 같다 — 탈주는 패배·탈주 수로 따로 남는다.</li>
 *   <li>D-299 — 판 전체에 곱하는 배율({@code factor}). 봇이 낀 판은 {@link #BOT_MATCH_FACTOR}(오를 때도 내릴 때도 — 내릴 때는
 *       절반 위에 더 곱한다), 사람끼리는 1. 어느 판이 어느 배율인지는 {@link RatedMatchPolicy#eloFactor} 가 정한다.</li>
 *   <li>D-301 — 참가 점수({@link #participationBonus}). 위 계산과 별개로 판마다 더하는 고정값이라 계산 결과에는 들어 있지 않다 —
 *       {@link UserGameStatsService#record(long, String, boolean, Integer, boolean, double)} 가 저장할 때 더한다.</li>
 * </ul>
 */
public final class EloCalculator {

    public static final int DEFAULT_K = 32;
    public static final int NEW_PLAYER_K = 40;
    public static final int NEW_PLAYER_THRESHOLD = 30;
    /** D-297 — 레이팅이 내려갈 때 곱하는 비율(오를 때는 그대로). */
    public static final double LOSS_FACTOR = 0.5;
    /** D-299 — 봇이 낀 판에서 레이팅 변화에 곱하는 비율("확실히 적게" — 사람끼리의 1/4). */
    public static final double BOT_MATCH_FACTOR = 0.25;
    /** 사람끼리의 판 — 줄이지 않는다. */
    public static final double FULL_FACTOR = 1.0;
    /** D-301 — 사람끼리의 판을 끝까지 마친 사람에게 결과와 상관없이 더하는 점수. */
    public static final int PARTICIPATION_BONUS = 6;
    /** D-301 — 봇이 낀 판(연습 판 포함)의 참가 점수. */
    public static final int BOT_MATCH_PARTICIPATION_BONUS = 2;

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
        return applyMatch(teamA, teamB, winnerIsTeamA, FULL_FACTOR);
    }

    /**
     * D-299 — {@link #applyMatch(List, List, boolean)} 에 판 배율을 곱한 것.
     *
     * @param factor 이 판의 레이팅 변화 배율(0 보다 크고 1 이하 — {@link RatedMatchPolicy#eloFactor})
     */
    public static Map<Long, Integer> applyMatch(List<PlayerInput> teamA,
                                                 List<PlayerInput> teamB,
                                                 boolean winnerIsTeamA,
                                                 double factor) {
        requireFactor(factor);
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
            int delta = delta(k * (scoreA - expectedA), factor);
            result.put(p.userId(), p.currentRating() + delta);
        }
        for (PlayerInput p : teamB) {
            int k = kFactor(p.gamesPlayed());
            int delta = delta(k * (scoreB - expectedB), factor);
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
     * {@code K_i / (n−1)} 로 합산한다. 2인이면 일반 ELO 와 같다. D-297 — 내려가는 몫은 절반이라 증감 합은 보존되지 않는다.
     *
     * @return userId → newRating 맵 (전원 포함)
     */
    public static Map<Long, Integer> applyFreeForAll(List<Placement> placements) {
        return applyFreeForAll(placements, FULL_FACTOR);
    }

    /**
     * D-299 — {@link #applyFreeForAll(List)} 에 판 배율을 곱한 것.
     *
     * @param factor 이 판의 레이팅 변화 배율(0 보다 크고 1 이하 — {@link RatedMatchPolicy#eloFactor})
     */
    public static Map<Long, Integer> applyFreeForAll(List<Placement> placements, double factor) {
        requireFactor(factor);
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
            int delta = delta(k * sum / (n - 1), factor);
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

    /** D-297 — 내려가는 몫은 {@link #LOSS_FACTOR} 만큼만. 반올림은 줄인 뒤에. */
    static int delta(double raw) {
        return delta(raw, FULL_FACTOR);
    }

    /** D-299 — 판 배율까지 곱한 뒤 한 번만 반올림한다(내려갈 때는 절반 × 배율). */
    static int delta(double raw, double factor) {
        double scaled = (raw < 0 ? raw * LOSS_FACTOR : raw) * factor;
        return (int) Math.round(scaled);
    }

    /**
     * D-301 — 참가 점수: 판을 많이 하면 계급이 오르게, ELO 계산 뒤에 더하는 고정값. 레이팅이 적용되는 판(배율 &gt; 0)을
     * 끝까지 마친 사람만 받는다. 이기든 지든 비기든 같다.
     *
     * @param factor   이 판의 배율({@link RatedMatchPolicy#eloFactor}) — 0 이면 ELO 미적용 판이라 0 점
     * @param deserted 탈주한 사람이면 0 점
     * @return 사람끼리 {@link #PARTICIPATION_BONUS}, 봇이 낀 판 {@link #BOT_MATCH_PARTICIPATION_BONUS}
     */
    public static int participationBonus(double factor, boolean deserted) {
        if (deserted || !(factor > 0.0)) {
            return 0;
        }
        return factor < FULL_FACTOR ? BOT_MATCH_PARTICIPATION_BONUS : PARTICIPATION_BONUS;
    }

    private static void requireFactor(double factor) {
        if (!(factor > 0.0 && factor <= 1.0)) {
            throw new IllegalArgumentException("Elo factor must be in (0, 1]: " + factor);
        }
    }

    public static int kFactor(int gamesPlayed) {
        return gamesPlayed < NEW_PLAYER_THRESHOLD ? NEW_PLAYER_K : DEFAULT_K;
    }

    /**
     * D-305 — {@link #applyMatch(List, List, boolean, double)} 의 결과를 <b>증감</b>(새 값 − 입력값)으로. 기록기는 이 값을
     * {@link UserGameStatsService#record(long, String, boolean, Integer, boolean, double)} 에 넘긴다 — 저장은 "지금 값 + 증감"이라
     * 같은 사람의 두 판이 동시에 끝나도 서로의 변화를 덮어쓰지 않는다(절대값으로 저장하면 뒤에 쓰는 판이 앞 판을 지운다).
     */
    public static Map<Long, Integer> matchDeltas(List<PlayerInput> teamA, List<PlayerInput> teamB,
                                                 boolean winnerIsTeamA, double factor) {
        Map<Long, Integer> ratings = applyMatch(teamA, teamB, winnerIsTeamA, factor);
        Map<Long, Integer> out = new java.util.HashMap<>();
        for (PlayerInput p : teamA) {
            out.put(p.userId(), ratings.get(p.userId()) - p.currentRating());
        }
        for (PlayerInput p : teamB) {
            out.put(p.userId(), ratings.get(p.userId()) - p.currentRating());
        }
        return out;
    }

    /** D-305 — {@link #applyFreeForAll(List, double)} 의 결과를 증감으로({@link #matchDeltas} 와 같은 이유). */
    public static Map<Long, Integer> freeForAllDeltas(List<Placement> placements, double factor) {
        Map<Long, Integer> ratings = applyFreeForAll(placements, factor);
        Map<Long, Integer> out = new java.util.HashMap<>();
        for (Placement p : placements) {
            out.put(p.player().userId(), ratings.get(p.player().userId()) - p.player().currentRating());
        }
        return out;
    }
}
