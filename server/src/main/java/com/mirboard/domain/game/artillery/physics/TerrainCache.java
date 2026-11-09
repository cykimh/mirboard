package com.mirboard.domain.game.artillery.physics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 생성한 원본 맵의 작은 프로세스 캐시. 맵은 (시드, 인원)의 순수 함수라 캐시가 틀릴 수 없고, 비워져도 다시 만들면 된다 — 정합성이 아니라
 * 비용(맵 생성)만 줄인다. 원본은 공유하므로 꺼낸 쪽은 {@link Terrain#copy()} 로 쓴다.
 */
public final class TerrainCache {

    private static final int CAPACITY = 64;

    private record Key(long seed, int players) {
    }

    private static final Map<Key, MapGenerator.GeneratedMap> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, MapGenerator.GeneratedMap> eldest) {
            return size() > CAPACITY;
        }
    };

    private TerrainCache() {
    }

    public static MapGenerator.GeneratedMap base(long seed, int players) {
        Key key = new Key(seed, players);
        synchronized (CACHE) {
            MapGenerator.GeneratedMap hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        MapGenerator.GeneratedMap made = MapGenerator.generate(seed, players);
        synchronized (CACHE) {
            CACHE.put(key, made);
        }
        return made;
    }
}
