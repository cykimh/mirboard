package com.mirboard.domain.game.cloudroute.board;

import java.util.ArrayList;
import java.util.List;

/**
 * 구름항로 판 24칸 (`docs/rules-cloudroute.md` §2). 진행은 칸 번호가 커지는 쪽이고 23 다음은 0 이다.
 *
 * <p>칸 표는 코드 상수다 — 판은 게임 구조라 방 설정으로 바꾸지 않는다. 수치(땅값·통행료)를 바꾸면 룰 정본 §2·§6.3 을 같이 고친다.
 */
public final class Board {

    public static final int SIZE = 24;

    public static final int START = 0;
    public static final int WHIRLPOOL = 6;
    public static final int CUSTOMS = 9;
    public static final int FESTIVAL = 12;
    public static final int STATION = 18;

    /** 섬 하나의 기본 통행료 [땅만, 천막, 상점, 풍등탑] — 땅값 × [0.2, 0.6, 1.4, 2.8] 을 10G 단위로 반올림한 값(§6.3). */
    private static List<Integer> tolls(int... values) {
        return List.of(values[0], values[1], values[2], values[3]);
    }

    private static final List<Tile> TILES;

    static {
        List<Tile> t = new ArrayList<>(SIZE);
        t.add(Tile.special(0, "출발 항구", TileKind.START));
        t.add(Tile.land(1, "이끼 나루", Island.MOSS, 60, tolls(10, 40, 80, 170)));
        t.add(Tile.land(2, "이끼 언덕", Island.MOSS, 60, tolls(10, 40, 80, 170)));
        t.add(Tile.special(3, "바람 편지", TileKind.LETTER));
        t.add(Tile.land(4, "조개 모래톱", Island.SHELL, 80, tolls(20, 50, 110, 220)));
        t.add(Tile.land(5, "조개 시장", Island.SHELL, 80, tolls(20, 50, 110, 220)));
        t.add(Tile.special(6, "소용돌이", TileKind.WHIRLPOOL));
        t.add(Tile.land(7, "버섯 숲길", Island.MUSHROOM, 100, tolls(20, 60, 140, 280)));
        t.add(Tile.land(8, "버섯 마을", Island.MUSHROOM, 100, tolls(20, 60, 140, 280)));
        t.add(Tile.special(9, "세관", TileKind.CUSTOMS));
        t.add(Tile.land(10, "풍차 들판", Island.WINDMILL, 120, tolls(20, 70, 170, 340)));
        t.add(Tile.land(11, "풍차 방앗간", Island.WINDMILL, 120, tolls(20, 70, 170, 340)));
        t.add(Tile.special(12, "등불 축제", TileKind.FESTIVAL));
        t.add(Tile.land(13, "유리 공방", Island.GLASS, 150, tolls(30, 90, 210, 420)));
        t.add(Tile.land(14, "유리 다리", Island.GLASS, 150, tolls(30, 90, 210, 420)));
        t.add(Tile.special(15, "바람 편지", TileKind.LETTER));
        t.add(Tile.land(16, "별사과 과수원", Island.STAR_APPLE, 170, tolls(30, 100, 240, 480)));
        t.add(Tile.land(17, "별사과 장터", Island.STAR_APPLE, 170, tolls(30, 100, 240, 480)));
        t.add(Tile.special(18, "바람 정류장", TileKind.STATION));
        t.add(Tile.land(19, "용암 온천", Island.LAVA, 200, tolls(40, 120, 280, 560)));
        t.add(Tile.land(20, "용암 대장간", Island.LAVA, 200, tolls(40, 120, 280, 560)));
        t.add(Tile.special(21, "바람 편지", TileKind.LETTER));
        t.add(Tile.land(22, "구름왕관 정원", Island.CROWN, 240, tolls(50, 140, 340, 670)));
        t.add(Tile.land(23, "구름왕관 성채", Island.CROWN, 240, tolls(50, 140, 340, 670)));
        TILES = List.copyOf(t);
    }

    private Board() {
    }

    public static List<Tile> tiles() {
        return TILES;
    }

    public static Tile tile(int id) {
        return TILES.get(id);
    }

    public static boolean isLand(int id) {
        return TILES.get(id).kind() == TileKind.LAND;
    }

    /** 같은 섬의 다른 칸. 땅이 아니면 예외. */
    public static int partner(int id) {
        Tile tile = TILES.get(id);
        if (tile.kind() != TileKind.LAND) {
            throw new IllegalArgumentException("not a land tile: " + id);
        }
        for (Tile other : TILES) {
            if (other.id() != id && other.island() == tile.island()) {
                return other.id();
            }
        }
        throw new IllegalStateException("island without a partner: " + tile.island());
    }

    /** {@code from} 에서 앞으로 {@code steps} 칸. */
    public static int forward(int from, int steps) {
        return Math.floorMod(from + steps, SIZE);
    }

    /** {@code from} 에서 앞으로 가서 {@code to} 에 닿는 거리(1~24 — 같은 칸이면 한 바퀴). */
    public static int distanceForward(int from, int to) {
        int d = Math.floorMod(to - from, SIZE);
        return d == 0 ? SIZE : d;
    }
}
