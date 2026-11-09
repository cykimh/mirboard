package com.mirboard.domain.game.cloudroute.board;

import java.util.List;

/**
 * 판의 칸 하나 (`docs/rules-cloudroute.md` §2). 땅이 아니면 {@code island} 는 null, 가격·통행료는 0·빈 목록이다.
 *
 * @param buildCost 건설 한 단계 비용 = 땅값의 50%
 * @param tolls     단계별 기본 통행료 [땅만, 천막, 상점, 풍등탑]
 */
public record Tile(int id, String name, TileKind kind, Island island, int price, int buildCost, List<Integer> tolls) {

    public Tile {
        tolls = List.copyOf(tolls);
    }

    static Tile land(int id, String name, Island island, int price, List<Integer> tolls) {
        return new Tile(id, name, TileKind.LAND, island, price, price / 2, tolls);
    }

    static Tile special(int id, String name, TileKind kind) {
        return new Tile(id, name, kind, null, 0, 0, List.of());
    }
}
