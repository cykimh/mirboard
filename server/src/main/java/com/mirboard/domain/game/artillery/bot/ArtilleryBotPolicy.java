package com.mirboard.domain.game.artillery.bot;

import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.physics.Body;
import com.mirboard.domain.game.artillery.physics.Fixed;
import com.mirboard.domain.game.artillery.physics.SplitMix64;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.ShotReport;
import com.mirboard.domain.game.artillery.state.Unit;
import java.util.ArrayList;
import java.util.List;

/**
 * 씨앗포 봇 "보통" (`docs/plans/artillery.md` §7, D-222). 서버와 같은 순수 엔진으로 후보 발사를 시뮬레이션해 가장 좋은 수를 찾고,
 * 사람 같은 <b>오차</b>를 얹는다 — 숨긴 정보가 없어 찾은 수를 그대로 쏘면 백발백중이기 때문이다.
 *
 * <p><b>공개 정보만 쓴다</b>(D-118·D-119): 시뮬레이션은 지금 상태(지형·포대·지금 바람)에 엔진의 {@code apply} 를 돌릴 뿐이고,
 * 엔진이 시드에서 쓰는 것은 공개된 지형의 원본뿐이다(다음 차례 바람은 보지 않는다 — 한 발만 본다). 오차는 (시드·차례·좌석)에서
 * 정해져 같은 상태면 같은 수다. 시간이 아니라 <b>시뮬레이션 횟수</b>로 예산을 잘라 부하와 무관하게 결정적이다.
 *
 * <p>탐색: (1) 표적 쪽 반원 거친 격자 → (2) 쓸 만한 수가 없으면 좌우 15·30칸 옮겨서 다시 → (3) 최고점 주변 세밀 격자. 점수 =
 * 적 피해 × 10 + 적 탈락 500 − 자기 피해 × 15 − 자기 탈락 5000 − 가장 가까운 적과 폭발점 거리(칸).
 */
public final class ArtilleryBotPolicy {

    /** 한 수에 돌리는 시뮬레이션 상한. */
    public static final int SIM_BUDGET = 900;
    /** "보통" 오차 — 각도 ±2°, 힘 ±2. */
    static final int ANGLE_ERROR = 2;
    static final int POWER_ERROR = 2;
    private static final int[] MOVES = {-30, -15, 15, 30};
    private static final int GOOD_ENOUGH = 50;
    private static final ArtilleryEngine SIM = new ArtilleryEngine();

    private ArtilleryBotPolicy() {
    }

    public static ArtilleryAction choose(ArtilleryState state, int seat) {
        Search search = new Search(state, seat);
        Unit me = state.unit(seat);
        Unit target = pickTarget(state, seat);
        if (target == null) {
            return new ArtilleryAction.Skip();
        }
        boolean right = target.x() >= me.x();
        Shell shell = me.pumpkins() > 0 && target.hp() > Shell.PEA.maxDamage() ? Shell.PUMPKIN : Shell.PEA;

        search.grid(0, right, 5, 4, shell);
        if (search.bestScore < GOOD_ENOUGH) {
            for (int move : MOVES) {
                search.grid(move, right, 10, 8, shell);
            }
        }
        ArtilleryAction.Fire best = search.best;
        if (best == null) {
            return new ArtilleryAction.Skip();
        }
        search.refine(best);
        best = search.best;

        // 오차 (결정적)
        long noise = SplitMix64.mix(state.seed(), 2_000_000L + state.turnNo() * 8L + seat);
        int da = (int) Long.remainderUnsigned(noise, 2 * ANGLE_ERROR + 1) - ANGLE_ERROR;
        int dp = (int) Long.remainderUnsigned(noise >>> 20, 2 * POWER_ERROR + 1) - POWER_ERROR;
        int angle = Math.clamp(best.angle() + da, 0, 180);
        int power = Math.clamp(best.power() + dp, 1, 100);
        return new ArtilleryAction.Fire(best.moveDx(), angle, power, best.shell());
    }

    /** 체력 낮은 적, 같으면 가까운 적. */
    static Unit pickTarget(ArtilleryState state, int seat) {
        Unit me = state.unit(seat);
        Unit best = null;
        for (Unit u : state.units()) {
            if (!u.alive() || u.seat() == seat) {
                continue;
            }
            if (best == null || u.hp() < best.hp()
                    || (u.hp() == best.hp() && Math.abs(u.x() - me.x()) < Math.abs(best.x() - me.x()))) {
                best = u;
            }
        }
        return best;
    }

    private static final class Search {
        final ArtilleryState state;
        final int seat;
        int sims;
        ArtilleryAction.Fire best;
        int bestScore = Integer.MIN_VALUE;

        Search(ArtilleryState state, int seat) {
            this.state = state;
            this.seat = seat;
        }

        void grid(int move, boolean right, int angleStep, int powerStep, Shell shell) {
            int from = right ? angleStep : 90 + angleStep;
            int to = right ? 90 - angleStep : 180 - angleStep;
            for (int a = from; a <= to; a += angleStep) {
                for (int p = 10; p <= 100; p += powerStep) {
                    try_(new ArtilleryAction.Fire(move, a, p, shell));
                }
            }
        }

        void refine(ArtilleryAction.Fire around) {
            for (int da = -4; da <= 4; da++) {
                for (int dp = -3; dp <= 3; dp++) {
                    if (da == 0 && dp == 0) {
                        continue;
                    }
                    int a = around.angle() + da;
                    int p = around.power() + dp;
                    if (a < 0 || a > 180 || p < 1 || p > 100) {
                        continue;
                    }
                    try_(new ArtilleryAction.Fire(around.moveDx(), a, p, around.shell()));
                }
            }
        }

        void try_(ArtilleryAction.Fire fire) {
            if (sims >= SIM_BUDGET) {
                return;
            }
            sims++;
            ArtilleryEngine.Result r = SIM.apply(state, seat, fire, 0);
            int score = score(r.newState());
            if (score > bestScore) {
                bestScore = score;
                best = fire;
            }
        }

        int score(ArtilleryState after) {
            ShotReport report = after.lastShot();
            int score = 0;
            int nearest = 1000;
            for (Unit before : state.units()) {
                if (!before.alive()) {
                    continue;
                }
                Unit now = after.unit(before.seat());
                int lost = before.hp() - now.hp();
                boolean died = !now.alive();
                if (before.seat() == seat) {
                    score -= lost * 15 + (died ? 5000 : 0);
                } else {
                    score += lost * 10 + (died ? 500 : 0);
                    if (report != null && report.shot() != null && report.shot().crater() != null) {
                        long dx = before.x() - report.shot().impactX();
                        long dy = Body.centerY(before.footY()) - report.shot().impactY();
                        nearest = Math.min(nearest, Fixed.isqrt(dx * dx + dy * dy));
                    }
                }
            }
            return score - nearest;
        }
    }

    /** 테스트용 — 오차 없이 찾은 수. */
    static List<Integer> searchCost(ArtilleryState state, int seat) {
        Search s = new Search(state, seat);
        Unit target = pickTarget(state, seat);
        boolean right = target != null && target.x() >= state.unit(seat).x();
        s.grid(0, right, 5, 4, Shell.PEA);
        if (s.bestScore < GOOD_ENOUGH) {
            for (int move : MOVES) {
                s.grid(move, right, 10, 8, Shell.PEA);
            }
        }
        if (s.best != null) {
            s.refine(s.best);
        }
        return new ArrayList<>(List.of(s.sims, s.bestScore));
    }
}
