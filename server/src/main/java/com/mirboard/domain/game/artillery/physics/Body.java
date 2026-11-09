package com.mirboard.domain.game.artillery.physics;

/**
 * 포대 몸 기하 (`docs/rules-artillery.md` §3·§6.3). 위치는 발 칸 {@code (x, footY)} 정수다.
 */
public final class Body {

    /** 몸 중심은 발에서 5칸 위. */
    public static final int CENTER_ABOVE_FOOT = 5;
    /** 맞는 범위 반경(칸). */
    public static final int HIT_RADIUS = 6;
    /** 받침 판정 반폭 — 발밑 줄의 x−2 ~ x+2. */
    public static final int FOOT_HALF = 2;
    /** 몸 높이(걷기 머리 공간). */
    public static final int HEIGHT = 8;
    /** 포신 길이(칸). */
    public static final int BARREL = 10;
    /** 낙하 피해가 시작되는 칸 수. */
    public static final int SAFE_FALL = 20;

    private Body() {
    }

    public static int centerY(int footY) {
        return footY - CENTER_ABOVE_FOOT;
    }

    public static boolean supported(Terrain t, int x, int footY) {
        for (int dx = -FOOT_HALF; dx <= FOOT_HALF; dx++) {
            if (t.solid(x + dx, footY + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 낙하 결과. {@code intoCloud} 면 {@code toY} 는 수면에 닿은 줄이다. */
    public record Fall(int fromY, int toY, int distance, boolean intoCloud) {
        public int damage() {
            return intoCloud ? 0 : Math.max(0, distance - SAFE_FALL) / 2;
        }
    }

    /** 받침이 생길 때까지 내린다. 처음부터 서 있으면 distance 0. 발이 수면 이상이 되면 구름 바다. */
    public static Fall fall(Terrain t, int x, int footY, int cloudLevel) {
        int y = footY;
        if (y >= cloudLevel) {
            return new Fall(footY, y, 0, true);
        }
        while (!supported(t, x, y)) {
            y++;
            if (y >= cloudLevel) {
                return new Fall(footY, y, y - footY, true);
            }
        }
        return new Fall(footY, y, y - footY, false);
    }
}
