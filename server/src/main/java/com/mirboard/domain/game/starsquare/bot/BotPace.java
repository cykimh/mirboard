package com.mirboard.domain.game.starsquare.bot;

import com.mirboard.domain.game.starsquare.puzzle.Band;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.state.BotPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 봇 페이스 모델(`docs/rules-starsquare.md` §10). 봇은 <b>수를 고르지 않는다</b> — 퍼즐이 시작될 때 완성 시각과 채울 순서를 정하고,
 * 엔진 타이머가 이정표마다 정답 칸을 채운다. 다른 좌석의 판을 보지 않으므로 "공개 정보만" 원칙(D-118)과 부딪히지 않는다.
 *
 * <p>결정적이다 — 같은 매치 시드·퍼즐 번호·좌석이면 같은 계획(난수는 그 셋에서 만든 {@link SplittableRandom}).
 */
public final class BotPace {

    /** 흔들림 범위 [수치]. */
    public static final double JITTER_MIN = 0.8;
    public static final double JITTER_MAX = 1.25;

    private BotPace() {
    }

    /**
     * 띠 기준 시간(ms) [수치] — 사람 풀이 시간 추정(S1.5, D-285): 사람 기준점 4×4 쉬움 77초(표본 1판)를 띠마다
     * (빈 칸 수 / 8) × (풀이 노드 중앙값 / 9) × (2 − 줄 채우기 비율 중앙값) 으로 외삽했다. 보통 봇(배수 1)이 이 시간 근처에 푼다.
     * 운영 뒤 실제 판 길이로 다시 맞춘다.
     */
    public static long baseMillis(Band band) {
        return switch (band) {
            case THREE_EASY -> 165_000L;
            case FOUR_EASY -> 77_000L;
            case FOUR_NORMAL -> 140_000L;
            case FIVE_HARD -> 400_000L;
        };
    }

    public static BotPlan plan(long matchSeed, int puzzleIndex, int seat, Band band, BotLevel level, Grid clues) {
        return plan(matchSeed, puzzleIndex, seat, band, level, clues, 1.0);
    }

    /** @param timeScale 시간 배율(운영 1 — 통합 테스트만 줄인다, {@code StarSquareConfig#timeScale}) */
    public static BotPlan plan(long matchSeed, int puzzleIndex, int seat, Band band, BotLevel level, Grid clues,
                               double timeScale) {
        SplittableRandom rng = new SplittableRandom(mix(matchSeed, puzzleIndex, seat));
        double jitter = JITTER_MIN + (JITTER_MAX - JITTER_MIN) * rng.nextDouble();
        long solveAfter = Math.max(1L, Math.round(baseMillis(band) * level.factor() * jitter * timeScale));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < clues.size(); i++) {
            if (clues.isEmpty(i)) {
                order.add(i);
            }
        }
        for (int i = order.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Integer t = order.get(i);
            order.set(i, order.get(j));
            order.set(j, t);
        }
        int milestones = Math.max(1, Math.min(BotPlan.MAX_MILESTONES, order.size()));
        return new BotPlan(solveAfter, order, milestones, 0);
    }

    private static long mix(long seed, int index, int seat) {
        long z = seed ^ (0x9E3779B97F4A7C15L * (index + 1)) ^ (0xC2B2AE3D27D4EB4FL * (seat + 1));
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
