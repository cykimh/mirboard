package com.mirboard.domain.game.artillery.physics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 물리 코어 단위 테스트 (`docs/rules-artillery.md` §1·§5·§6). 평평한 바닥을 직접 칠한 지형으로 본다. */
class PhysicsTest {

    /** y ≥ floor 를 전부 흙으로. */
    static Terrain flat(int floor) {
        Terrain t = new Terrain();
        for (int y = floor; y < Fixed.HEIGHT; y++) {
            for (int x = 0; x < Fixed.WIDTH; x++) {
                t.set(x, y);
            }
        }
        return t;
    }

    @Test
    void trig_table_and_isqrt() {
        assertThat(Fixed.sin(0)).isZero();
        assertThat(Fixed.sin(90)).isEqualTo(65536);
        assertThat(Fixed.cos(180)).isEqualTo(-65536);
        assertThat(Fixed.cos(90)).isZero();
        for (int d = 0; d <= 180; d++) {
            assertThat(Fixed.sin(d)).isEqualTo(Fixed.sin(180 - d));
            assertThat(Fixed.cos(d)).isEqualTo(-Fixed.cos(180 - d));
        }
        assertThat(Fixed.isqrt(0)).isZero();
        assertThat(Fixed.isqrt(35)).isEqualTo(5);
        assertThat(Fixed.isqrt(36)).isEqualTo(6);
        assertThat(Fixed.isqrt(Long.MAX_VALUE / 4)).isEqualTo(1518500249);
    }

    @Test
    void splitmix_is_stateless_and_matches_reference() {
        // 참조값: 공개 SplitMix64(시드 0, 첫 출력) = 0xE220A8397B1DCDAF
        assertThat(SplitMix64.mix(0, 0)).isEqualTo(0xE220A8397B1DCDAFL);
        assertThat(SplitMix64.mix(42, 7)).isEqualTo(SplitMix64.mix(42, 7));
        assertThat(SplitMix64.below(42, 7, 21)).isBetween(0, 20);
    }

    @Test
    void carve_clears_exactly_the_disc() {
        Terrain t = flat(200);
        int before = t.solidCount();
        t.carve(300, 200, 10);
        assertThat(t.solid(300, 200)).isFalse();
        assertThat(t.solid(300, 210)).isFalse();
        assertThat(t.solid(300, 211)).isTrue();
        assertThat(t.solid(308, 206)).isFalse();   // 64 + 36 = 100 ≤ 100
        assertThat(t.solid(309, 206)).isTrue();
        assertThat(before - t.solidCount()).isPositive();
    }

    @Test
    void shell_at_45_degrees_lands_where_the_range_formula_says() {
        Terrain t = flat(300);
        // 발 299, 몸 중심 294. 힘 70, 45° — 이론 사거리 ≈ 408칸(같은 높이 기준), 포구·높이차로 조금 더 간다
        Ballistics.Trace tr = Ballistics.fire(t, List.of(), 0, 50, 299, 45, 70, 0, 360);
        assertThat(tr.end()).isEqualTo(Ballistics.End.TERRAIN);
        assertThat(tr.impactY()).isEqualTo(300);
        assertThat(tr.impactX() - 50).isBetween(400, 440);
        assertThat(tr.ticks()).isBetween(120, 150);
    }

    @Test
    void wind_pushes_the_shell_and_mirror_angles_are_mirror_shots() {
        Terrain t = flat(300);
        Ballistics.Trace calm = Ballistics.fire(t, List.of(), 0, 320, 299, 70, 60, 0, 360);
        Ballistics.Trace east = Ballistics.fire(t, List.of(), 0, 320, 299, 70, 60, 10, 360);
        Ballistics.Trace mirror = Ballistics.fire(t, List.of(), 0, 320, 299, 110, 60, 0, 360);
        assertThat(east.impactX()).isGreaterThan(calm.impactX() + 30);
        // 거울: 320 − d ↔ 320 + d (칸 내림 때문에 ±1)
        assertThat(mirror.impactX() + calm.impactX()).isBetween(639, 641);
    }

    @Test
    void straight_up_comes_back_and_hits_the_shooter_after_the_grace_ticks() {
        Terrain t = flat(300);
        List<Ballistics.Target> targets = List.of(new Ballistics.Target(0, 320, 299));
        Ballistics.Trace tr = Ballistics.fire(t, targets, 0, 320, 299, 90, 40, 0, 360);
        assertThat(tr.end()).isEqualTo(Ballistics.End.UNIT);
        assertThat(tr.hitSeat()).isZero();
        assertThat(tr.ticks()).isGreaterThan(Ballistics.SELF_GRACE_TICKS);
    }

    @Test
    void sideways_out_and_into_the_cloud_do_not_explode() {
        Terrain empty = new Terrain();
        Ballistics.Trace out = Ballistics.fire(empty, List.of(), 0, 600, 100, 10, 100, 0, 360);
        assertThat(out.end()).isEqualTo(Ballistics.End.OUT);
        Ballistics.Trace cloud = Ballistics.fire(empty, List.of(), 0, 320, 100, 90, 10, 0, 200);
        assertThat(cloud.end()).isEqualTo(Ballistics.End.CLOUD);
        assertThat(cloud.impactY()).isGreaterThanOrEqualTo(200);
        assertThat(out.exploded()).isFalse();
    }

    @Test
    void walking_on_flat_ground_costs_one_fuel_per_step_and_stops_when_empty() {
        Terrain t = flat(300);
        Walker.Walk w = Walker.walk(t, 100, 299, 40, 100, 360);
        assertThat(w.x()).isEqualTo(140);
        assertThat(w.footY()).isEqualTo(299);
        assertThat(w.fuelLeft()).isEqualTo(60);
        Walker.Walk tired = Walker.walk(t, 100, 299, -100, 30, 360);
        assertThat(tired.x()).isEqualTo(70);
        assertThat(tired.fuelLeft()).isZero();
    }

    @Test
    void walking_climbs_three_and_stops_at_a_wall() {
        Terrain t = flat(300);
        for (int y = 297; y < 300; y++) {          // 3칸 계단
            for (int x = 110; x < 120; x++) {
                t.set(x, y);
            }
        }
        for (int y = 280; y < 300; y++) {          // 20칸 벽
            for (int x = 130; x < 135; x++) {
                t.set(x, y);
            }
        }
        Walker.Walk w = Walker.walk(t, 100, 299, 50, 100, 360);
        assertThat(w.x()).isEqualTo(129);
        assertThat(w.fall()).isNull();
        // 1칸 × 29걸음 + 계단 오를 때 3
        assertThat(w.fuelLeft()).isEqualTo(100 - 29 - 3);
    }

    @Test
    void walking_off_a_cliff_falls_and_stops() {
        Terrain t = new Terrain();
        for (int x = 0; x < 200; x++) {
            t.set(x, 100);
        }
        for (int x = 0; x < Fixed.WIDTH; x++) {
            t.set(x, 300);
        }
        Walker.Walk w = Walker.walk(t, 190, 99, 30, 100, 360);
        assertThat(w.fall()).isNotNull();
        assertThat(w.fall().distance()).isEqualTo(200);
        assertThat(w.fall().damage()).isEqualTo(90);
        assertThat(w.footY()).isEqualTo(299);
        assertThat(w.x()).isEqualTo(202);   // 받침 반폭 2 — 가장자리에서 2칸 더 서 있다
    }

    @Test
    void falling_below_the_cloud_is_into_the_cloud() {
        Terrain t = new Terrain();
        Body.Fall f = Body.fall(t, 100, 50, 200);
        assertThat(f.intoCloud()).isTrue();
        assertThat(f.toY()).isEqualTo(200);
    }
}
