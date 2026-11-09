package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.physics.MapGenerator;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Crater;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 비용 (`docs/rules-artillery.md` §12 · `docs/plans/artillery.md` §4.5) — 구멍 60개가 쌓인 판에서 한 발 해소(지형 재구성 포함)와
 * 캐시 없는 맵 생성 시간을 잰다. 상한은 운영 사양(C1 JIT)을 감안해 넉넉히 — 숫자는 출력으로 기록한다.
 */
class ArtilleryCostTest {

    @Test
    void a_shot_on_a_well_cratered_map_resolves_well_under_the_budget() {
        ArtilleryEngine engine = new ArtilleryEngine();
        ArtilleryState s = engine.startMatch(777L, 4, List.of()).newState();
        List<Crater> craters = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            craters.add(new Crater(40 + i * 9, 180 + (i * 37) % 80, i % 3 == 0 ? 24 : 14));
        }
        ArtilleryState cratered = new ArtilleryState(s.seed(), s.seatCount(), s.botSeats(), s.units(), craters,
                s.phase(), s.turnSeat(), s.lastSeat(), s.turnNo(), s.wind(), s.cloudLevel(), 0, 0, null, null);
        int seat = cratered.turnSeat();
        for (int i = 0; i < 300; i++) {      // 데우기
            engine.apply(cratered, seat, new ArtilleryAction.Fire(i % 40 - 20, i % 181, 30 + i % 70, Shell.PEA), 0);
        }
        int n = 2000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            engine.apply(cratered, seat, new ArtilleryAction.Fire(i % 40 - 20, i % 181, 30 + i % 70, Shell.PUMPKIN), 0);
        }
        double perShotMs = (System.nanoTime() - t0) / 1e6 / n;

        long g0 = System.nanoTime();
        int maps = 50;
        for (int i = 0; i < maps; i++) {
            MapGenerator.generate(10_000L + i, 4);
        }
        double perMapMs = (System.nanoTime() - g0) / 1e6 / maps;
        System.out.printf("[artillery-cost] shot(60 craters)=%.3fms map=%.3fms%n", perShotMs, perMapMs);
        assertThat(perShotMs).isLessThan(6.0);
        assertThat(perMapMs).isLessThan(20.0);
    }
}
