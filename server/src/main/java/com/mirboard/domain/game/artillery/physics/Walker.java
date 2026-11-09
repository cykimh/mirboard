package com.mirboard.domain.game.artillery.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * 걷기 (`docs/rules-artillery.md` §5.1). 한 걸음씩 오르막 3칸·내리막 3칸까지 지면을 따라가고, 벽·연료·전장 끝에서 멈춘다.
 * 받침을 잃으면 떨어지고 걷기를 멈춘다.
 */
public final class Walker {

    public static final int MIN_X = 3;
    public static final int MAX_X = Fixed.WIDTH - 4;
    public static final int MAX_STEP = 3;

    /**
     * @param path     걸음마다 도착 칸 (x, footY) 을 이어 붙인 목록(출발점 제외)
     * @param fall     걷다가 떨어졌으면 그 낙하, 아니면 null
     */
    public record Walk(int x, int footY, int fuelLeft, int steps, List<Integer> path, Body.Fall fall) {
        public Walk {
            path = List.copyOf(path);
        }
    }

    private Walker() {
    }

    public static Walk walk(Terrain t, int x, int footY, int moveDx, int fuel, int cloudLevel) {
        int dir = Integer.signum(moveDx);
        int remaining = Math.abs(moveDx);
        List<Integer> path = new ArrayList<>();
        int steps = 0;
        while (remaining-- > 0) {
            int nx = x + dir;
            if (nx < MIN_X || nx > MAX_X) {
                break;
            }
            int climb = -1;
            for (int k = 0; k <= MAX_STEP; k++) {
                if (clear(t, nx, footY - k)) {
                    climb = k;
                    break;
                }
            }
            if (climb < 0) {
                break;   // 벽
            }
            int cost = 1 + climb;
            if (cost > fuel) {
                break;
            }
            fuel -= cost;
            int ny = footY - climb;
            // 내리막 — 3칸까지는 걸어 내려간다(낙하 아님).
            int down = 0;
            while (down < MAX_STEP && !Body.supported(t, nx, ny + down) && clear(t, nx, ny + down + 1)) {
                down++;
            }
            if (Body.supported(t, nx, ny + down)) {
                ny += down;
            }
            x = nx;
            footY = ny;
            steps++;
            path.add(x);
            path.add(footY);
            if (footY >= cloudLevel) {
                // 걸어 내려가다 수면에 닿음 — 구름 바다(낙하 0칸).
                return new Walk(x, footY, fuel, steps, path, new Body.Fall(footY, footY, 0, true));
            }
            if (!Body.supported(t, x, footY)) {
                Body.Fall fall = Body.fall(t, x, footY, cloudLevel);
                return new Walk(x, fall.toY(), fuel, steps, path, fall);
            }
        }
        return new Walk(x, footY, fuel, steps, path, null);
    }

    /** 발 칸부터 위로 몸 높이만큼 빈칸인가. */
    static boolean clear(Terrain t, int x, int footY) {
        for (int y = footY - Body.HEIGHT + 1; y <= footY; y++) {
            if (t.solid(x, y)) {
                return false;
            }
        }
        return true;
    }
}
