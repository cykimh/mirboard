package com.mirboard.domain.game.yacht.bot;

import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.score.Scoring;
import com.mirboard.domain.game.yacht.state.TurnState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 요트 휴리스틱 봇 (설계 `docs/plans/yacht.md` §6.5). 공개 정보 뷰만 보고 난수를 쓰지 않는다 — 같은 뷰면 같은 수.
 *
 * <p><b>칸 가치</b> = 점수 − 그 칸의 기준 점수(보통 기대하는 값). 기준보다 낮게 쓰면 손해로 본다 — 요트·라지 스트레이트를
 * 일찍 0 으로 버리지 않고, 어쩔 수 없으면 손해가 가장 작은 칸에 버린다. 상단 칸은 보너스(63)가 아직이면 "눈 × 3"보다
 * 많이 쓸수록 가산한다.
 *
 * <p><b>굴림</b>: 홀드 마스크 31개(다섯 개 모두 홀드 제외)마다 홀드 안 한 주사위를 한 번 더 굴렸을 때의 "가장 좋은 칸 가치"
 * 기대값을 전수로 계산한다. 점수는 눈의 순서와 무관하므로 가능한 주사위 묶음 252가지의 칸 점수를 미리 계산해 두고, 결정마다
 * 그 252가지의 칸 가치만 다시 구한 뒤 결과마다 찾아 쓴다(결정 한 번에 경우 수 7⁵ − 1 조회). 지금 바로 쓰는 가치보다 기대값이
 * 크면 그 마스크로 굴린다 — 지금 홀드와 다르면 먼저 {@code HOLD}(구경꾼에게 무엇을 노리는지 보이게), 같으면 {@code ROLL}.
 * 아니면 기록.
 *
 * <p>방 액션 락 안에서 불리므로(`docs/plans/omok.md` D-179 메모) 결정 하나가 짧아야 한다 — 위 방식으로 결정당 1ms 안쪽이다.
 */
public final class YachtBotPolicy {

    /** 칸별 기준 점수 — 보통 판에서 그 칸에 기대하는 값(대략). 점수표 순서. */
    private static final double[] PAR = {2, 5, 8, 11, 14, 17, 22, 13, 15, 10, 8, 12};
    /** 보너스가 아직일 때 상단 칸의 "눈 × 3" 대비 초과분 가중. */
    private static final double BONUS_PUSH = 0.6;

    private static final Category[] CATEGORIES = Category.values();
    /** 6 진수 자리값 — 눈 v 하나가 묶음 키에 더하는 값은 POW[v - 1]. 눈별 개수가 5 이하라 자리 넘침이 없다. */
    private static final int[] POW = {1, 6, 36, 216, 1296, 7776};
    private static final int KEY_SPACE = 46_656;
    /** 묶음 키 → 묶음 번호(없으면 −1). */
    private static final int[] INDEX = new int[KEY_SPACE];
    /** 묶음 번호 → 칸별 점수. */
    private static final int[][] POINTS;

    static {
        Arrays.fill(INDEX, -1);
        List<int[]> table = new ArrayList<>();
        for (int a = 1; a <= 6; a++)
            for (int b = a; b <= 6; b++)
                for (int c = b; c <= 6; c++)
                    for (int d = c; d <= 6; d++)
                        for (int e = d; e <= 6; e++) {
                            Dice dice = Dice.of(a, b, c, d, e);
                            int[] points = new int[CATEGORIES.length];
                            for (Category cat : CATEGORIES) {
                                points[cat.ordinal()] = Scoring.points(cat, dice);
                            }
                            INDEX[POW[a - 1] + POW[b - 1] + POW[c - 1] + POW[d - 1] + POW[e - 1]] = table.size();
                            table.add(points);
                        }
        POINTS = table.toArray(int[][]::new);
    }

    private YachtBotPolicy() {
    }

    public static YachtAction choose(YachtBotView view) {
        if (view.rolls() == 0) {
            return YachtAction.Roll.plain();
        }
        Scorecard card = view.card();
        Category bestNow = bestCategory(card, view.dice());
        if (view.rolls() >= TurnState.MAX_ROLLS) {
            return new YachtAction.Score(bestNow);
        }
        double now = value(card, bestNow, Scoring.points(bestNow, view.dice()));
        double[] bestByCombo = bestValues(card);

        HoldMask bestMask = null;
        double bestExpected = Double.NEGATIVE_INFINITY;
        // 지금 홀드를 먼저 본다 — 같은 기대값이면 홀드를 바꾸지 않는다(쓸데없는 HOLD 를 줄인다).
        List<HoldMask> order = new ArrayList<>();
        order.add(view.held());
        for (HoldMask m : HoldMask.every()) {
            if (!m.equals(view.held())) {
                order.add(m);
            }
        }
        for (HoldMask mask : order) {
            if (mask.all()) {
                continue;
            }
            double expected = expectedAfterReroll(bestByCombo, view.dice(), mask);
            if (expected > bestExpected + 1e-9) {
                bestExpected = expected;
                bestMask = mask;
            }
        }
        if (bestMask == null || bestExpected <= now + 1e-9) {
            return new YachtAction.Score(bestNow);
        }
        if (!bestMask.equals(view.held())) {
            return new YachtAction.Hold(bestMask);
        }
        return YachtAction.Roll.plain();
    }

    /** 빈 칸 중 가치가 가장 큰 칸(같으면 점수표 위쪽). */
    static Category bestCategory(Scorecard card, Dice dice) {
        Category best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Category c : CATEGORIES) {
            if (card.filled(c)) {
                continue;
            }
            double v = value(card, c, Scoring.points(c, dice));
            if (v > bestValue + 1e-9) {
                best = c;
                bestValue = v;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No empty category");
        }
        return best;
    }

    static double value(Scorecard card, Category c, int points) {
        double v = points - PAR[c.ordinal()];
        if (c.upper() && card.bonus() == 0) {
            v += BONUS_PUSH * (points - 3.0 * c.face());
        }
        return v;
    }

    /** 묶음마다 이 점수표에서 가장 좋은 칸 가치. */
    private static double[] bestValues(Scorecard card) {
        double[] out = new double[POINTS.length];
        for (int i = 0; i < POINTS.length; i++) {
            double best = Double.NEGATIVE_INFINITY;
            for (Category c : CATEGORIES) {
                if (!card.filled(c)) {
                    best = Math.max(best, value(card, c, POINTS[i][c.ordinal()]));
                }
            }
            out[i] = best;
        }
        return out;
    }

    /** 홀드 안 한 주사위를 한 번 굴렸을 때 가장 좋은 칸 가치의 기대값 — 모든 결과를 같은 확률로 센다. */
    private static double expectedAfterReroll(double[] bestByCombo, Dice dice, HoldMask held) {
        int heldKey = 0;
        int free = 0;
        for (int i = 0; i < Dice.COUNT; i++) {
            if (held.isHeld(i)) {
                heldKey += POW[dice.face(i) - 1];
            } else {
                free++;
            }
        }
        double[] sum = {0};
        int outcomes = enumerate(bestByCombo, heldKey, free, sum);
        return sum[0] / outcomes;
    }

    private static int enumerate(double[] bestByCombo, int key, int remaining, double[] sum) {
        if (remaining == 0) {
            sum[0] += bestByCombo[INDEX[key]];
            return 1;
        }
        int count = 0;
        for (int face = 1; face <= Dice.FACES; face++) {
            count += enumerate(bestByCombo, key + POW[face - 1], remaining - 1, sum);
        }
        return count;
    }
}
