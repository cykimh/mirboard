package com.mirboard.domain.game.artillery.physics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 맵 생성 (`docs/rules-artillery.md` §2). */
class MapGeneratorTest {

    @Test
    void same_seed_same_map() {
        MapGenerator.GeneratedMap a = MapGenerator.generate(12345L, 4);
        MapGenerator.GeneratedMap b = MapGenerator.generate(12345L, 4);
        assertThat(a.terrain().hash()).isEqualTo(b.terrain().hash());
        assertThat(a.spawns()).isEqualTo(b.spawns());
        assertThat(MapGenerator.generate(12346L, 4).terrain().hash()).isNotEqualTo(a.terrain().hash());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6})
    void every_seed_gives_symmetric_islands_and_flat_spawns_for_every_player(int players) {
        for (long seed = 1; seed <= 300; seed++) {
            MapGenerator.GeneratedMap map = MapGenerator.generate(seed * 7919, players);
            Terrain t = map.terrain();
            assertThat(map.spawns()).hasSize(players);
            for (int x = 0; x < Fixed.WIDTH / 2; x += 7) {
                for (int y = 0; y < Fixed.HEIGHT; y += 5) {
                    assertThat(t.solid(x, y)).as("mirror seed=%d (%d,%d)", seed, x, y)
                            .isEqualTo(t.solid(Fixed.WIDTH - 1 - x, y));
                }
            }
            int lastX = -1;
            for (MapGenerator.Spawn s : map.spawns()) {
                assertThat(Body.supported(t, s.x(), s.footY())).isTrue();
                assertThat(Walker.clear(t, s.x(), s.footY())).isTrue();
                assertThat(s.x()).isGreaterThan(lastX);
                lastX = s.x();
            }
            assertThat(t.solidCount()).isGreaterThan(5_000);
        }
    }

    @Test
    void column_runs_round_trip() {
        Terrain t = MapGenerator.generate(99L, 3).terrain();
        t.carve(320, 200, 30);
        Terrain rebuilt = new Terrain();
        var runs = t.columnRuns();
        for (int x = 0; x < Fixed.WIDTH; x++) {
            int[] r = runs.get(x);
            for (int i = 0; i < r.length; i += 2) {
                for (int y = r[i]; y <= r[i + 1]; y++) {
                    rebuilt.set(x, y);
                }
            }
        }
        assertThat(rebuilt.hash()).isEqualTo(t.hash());
    }
}
