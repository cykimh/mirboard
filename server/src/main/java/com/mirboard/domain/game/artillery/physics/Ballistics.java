package com.mirboard.domain.game.artillery.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * 정수 탄도 (`docs/rules-artillery.md` §6.1). 반암시 오일러, 틱 = 1/60초. 틱마다 이동 선분을 1칸 이하로 쪼개 충돌을 찾는다.
 * 순수 함수 — 지형을 바꾸지 않는다(구멍은 부르는 쪽).
 */
public final class Ballistics {

    /** 쏜 포대 자신을 맞는 범위에서 빼는 틱 수. */
    public static final int SELF_GRACE_TICKS = 10;

    public enum End {
        TERRAIN, UNIT, OUT, CLOUD, EXPIRED
    }

    /** 표적 하나 — 살아 있는 포대의 몸 중심. */
    public record Target(int seat, int x, int footY) {
    }

    /**
     * @param path    2틱마다 한 점 + 끝점 (칸 x, y 를 이어 붙인 목록), 시작점(포구) 포함
     * @param impactX 폭발 칸(TERRAIN·UNIT 일 때만 의미)
     * @param hitSeat 직격 좌석, 아니면 −1
     */
    public record Trace(List<Integer> path, End end, int impactX, int impactY, int hitSeat, int ticks) {
        public Trace {
            path = List.copyOf(path);
        }

        public boolean exploded() {
            return end == End.TERRAIN || end == End.UNIT;
        }
    }

    private Ballistics() {
    }

    public static Trace fire(Terrain t, List<Target> targets, int shooterSeat, int shooterX, int shooterFootY,
                             int angle, int power, int wind, int cloudLevel) {
        long x = Fixed.center(shooterX) + (long) Body.BARREL * Fixed.cos(angle);
        long y = Fixed.center(Body.centerY(shooterFootY)) - (long) Body.BARREL * Fixed.sin(angle);
        long v = power * Fixed.V_PER_POWER;
        long vx = (v * Fixed.cos(angle)) >> Fixed.SHIFT;
        long vy = -((v * Fixed.sin(angle)) >> Fixed.SHIFT);
        long ax = wind * Fixed.WIND_PER_UNIT;
        int floor = Math.min(Fixed.HEIGHT, cloudLevel);

        List<Integer> path = new ArrayList<>();
        path.add(Fixed.cell(x));
        path.add(Fixed.cell(y));

        // 포구 칸 자체가 막혔으면 그 자리에서 터진다(포신이 벽에 박힘).
        Trace atMuzzle = check(t, targets, shooterSeat, x, y, 0, floor, path);
        if (atMuzzle != null) {
            return atMuzzle;
        }
        for (int tick = 1; tick <= Fixed.MAX_TICKS; tick++) {
            vx += ax;
            vy += Fixed.GRAVITY;
            long dx = vx;
            long dy = vy;
            long n = Math.max(Math.abs(dx), Math.abs(dy)) / Fixed.ONE + 1;
            for (long k = 1; k <= n; k++) {
                long px = x + dx * k / n;
                long py = y + dy * k / n;
                Trace hit = check(t, targets, shooterSeat, px, py, tick, floor, path);
                if (hit != null) {
                    return hit;
                }
            }
            x += dx;
            y += dy;
            if (tick % 2 == 0) {
                path.add(Fixed.cell(x));
                path.add(Fixed.cell(y));
            }
        }
        addEnd(path, Fixed.cell(x), Fixed.cell(y));
        return new Trace(path, End.EXPIRED, Fixed.cell(x), Fixed.cell(y), -1, Fixed.MAX_TICKS);
    }

    private static Trace check(Terrain t, List<Target> targets, int shooterSeat, long px, long py, int tick,
                               int floor, List<Integer> path) {
        int cx = Fixed.cell(px);
        int cy = Fixed.cell(py);
        if (cx < 0 || cx >= Fixed.WIDTH) {
            addEnd(path, cx, cy);
            return new Trace(path, End.OUT, cx, cy, -1, tick);
        }
        if (cy >= floor) {
            addEnd(path, cx, cy);
            return new Trace(path, End.CLOUD, cx, cy, -1, tick);
        }
        for (Target target : targets) {
            if (target.seat() == shooterSeat && tick <= SELF_GRACE_TICKS) {
                continue;
            }
            long ddx = cx - target.x();
            long ddy = cy - Body.centerY(target.footY());
            if (ddx * ddx + ddy * ddy <= (long) Body.HIT_RADIUS * Body.HIT_RADIUS) {
                addEnd(path, cx, cy);
                return new Trace(path, End.UNIT, cx, cy, target.seat(), tick);
            }
        }
        if (cy >= 0 && t.solid(cx, cy)) {
            addEnd(path, cx, cy);
            return new Trace(path, End.TERRAIN, cx, cy, -1, tick);
        }
        return null;
    }

    private static void addEnd(List<Integer> path, int cx, int cy) {
        int n = path.size();
        if (n >= 2 && path.get(n - 2) == cx && path.get(n - 1) == cy) {
            return;
        }
        path.add(cx);
        path.add(cy);
    }
}
