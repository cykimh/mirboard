package com.mirboard.domain.game.artillery.state;

import com.mirboard.domain.game.artillery.rules.Shell;
import java.util.List;

/**
 * 발사 한 번의 재생 대본 (`docs/plans/artillery.md` §6.2). 클라는 이 순서대로 그린다: 이동 → (걷다 떨어짐) → 비행 → 폭발 → 낙하.
 * 모든 값은 결과값이다 — 체력은 {@code hpAfter}, 끝에 {@link #units} 전체.
 *
 * @param shot  발사하지 못했으면(걷다가 구름 바다에 빠짐) null
 */
public record ShotReport(int seat, int angle, int power, Shell shell, int wind,
                         Move move, Shot shot,
                         List<Damage> damages, List<Fall> falls, List<Eliminated> eliminated,
                         List<UnitView> units, long durationMs) {

    public ShotReport {
        damages = List.copyOf(damages);
        falls = List.copyOf(falls);
        eliminated = List.copyOf(eliminated);
        units = List.copyOf(units);
    }

    /** @param path 걸음마다 (x, footY) 를 이어 붙인 목록. {@code fall} 은 걷다 떨어진 경우. */
    public record Move(List<Integer> path, int toX, int toY, int fuelLeft, Fall fall) {
        public Move {
            path = List.copyOf(path);
        }
    }

    /** @param end TERRAIN·UNIT 이면 폭발({@code crater} 있음), OUT·CLOUD·EXPIRED 면 없음 */
    public record Shot(List<Integer> path, String end, int impactX, int impactY, Crater crater, int hitSeat, int ticks) {
        public Shot {
            path = List.copyOf(path);
        }
    }

    public record Damage(int seat, int amount, int hpAfter, boolean direct) {
    }

    public record Fall(int seat, int x, int fromY, int toY, int damage, int hpAfter, boolean intoCloud) {
    }

    public record Eliminated(int seat, Unit.Cause cause) {
    }

    public record UnitView(int seat, int x, int footY, int hp, boolean alive) {
        public static UnitView of(Unit u) {
            return new UnitView(u.seat(), u.x(), u.footY(), u.hp(), u.alive());
        }
    }
}
