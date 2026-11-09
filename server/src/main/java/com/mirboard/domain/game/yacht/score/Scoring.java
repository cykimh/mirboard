package com.mirboard.domain.game.yacht.score;

import com.mirboard.domain.game.yacht.dice.Dice;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 주사위 → 칸 점수 (`docs/rules-yacht.md` §5). 순수 함수만 있다.
 *
 * <p>후보 점수({@link #candidates})는 서버가 계산해 공개한다 — 클라는 점수를 계산하지 않는다(D-182).
 */
public final class Scoring {

    public static final int SMALL_STRAIGHT_POINTS = 15;
    public static final int LARGE_STRAIGHT_POINTS = 30;
    public static final int YACHT_POINTS = 50;

    private Scoring() {
    }

    public static int points(Category category, Dice dice) {
        int[] counts = dice.counts();
        return switch (category) {
            case ONES, TWOS, THREES, FOURS, FIVES, SIXES -> category.face() * counts[category.face()];
            case CHOICE -> dice.sum();
            case FOUR_OF_A_KIND -> maxCount(counts) >= 4 ? dice.sum() : 0;
            case FULL_HOUSE -> fullHouse(counts) ? dice.sum() : 0;
            case SMALL_STRAIGHT -> smallStraight(counts) ? SMALL_STRAIGHT_POINTS : 0;
            case LARGE_STRAIGHT -> largeStraight(counts) ? LARGE_STRAIGHT_POINTS : 0;
            case YACHT -> maxCount(counts) == Dice.COUNT ? YACHT_POINTS : 0;
        };
    }

    /** 빈 칸마다 지금 주사위의 점수(0 포함), 점수표 순서. */
    public static Map<Category, Integer> candidates(Scorecard card, Dice dice) {
        Map<Category, Integer> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            if (!card.filled(c)) {
                out.put(c, points(c, dice));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 즉시 점수가 가장 높은 빈 칸, 같으면 점수표 위쪽 칸 (§6 시간 초과 자동 기록). 빈 칸이 없으면 예외.
     */
    public static Category best(Scorecard card, Dice dice) {
        Category best = null;
        int bestPoints = -1;
        for (Category c : Category.values()) {
            if (card.filled(c)) {
                continue;
            }
            int p = points(c, dice);
            if (p > bestPoints) {
                best = c;
                bestPoints = p;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No empty category");
        }
        return best;
    }

    private static int maxCount(int[] counts) {
        int max = 0;
        for (int v = 1; v <= Dice.FACES; v++) {
            max = Math.max(max, counts[v]);
        }
        return max;
    }

    /** {3, 2} 또는 {5} — 5개 같음도 인정한다(D-182 하우스 룰). */
    private static boolean fullHouse(int[] counts) {
        boolean three = false;
        boolean two = false;
        for (int v = 1; v <= Dice.FACES; v++) {
            if (counts[v] == Dice.COUNT) {
                return true;
            }
            if (counts[v] == 3) {
                three = true;
            } else if (counts[v] == 2) {
                two = true;
            }
        }
        return three && two;
    }

    private static boolean smallStraight(int[] counts) {
        return run(counts, 1, 4) || run(counts, 2, 4) || run(counts, 3, 4);
    }

    private static boolean largeStraight(int[] counts) {
        return run(counts, 1, 5) || run(counts, 2, 5);
    }

    private static boolean run(int[] counts, int from, int length) {
        for (int v = from; v < from + length; v++) {
            if (counts[v] == 0) {
                return false;
            }
        }
        return true;
    }
}
