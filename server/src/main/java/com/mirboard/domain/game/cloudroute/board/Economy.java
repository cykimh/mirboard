package com.mirboard.domain.game.cloudroute.board;

/**
 * 돈 수치 (`docs/rules-cloudroute.md` 의 [수치]). S1 시뮬레이션으로 조정하며, 바꾸면 룰 정본을 같이 고친다.
 */
public final class Economy {

    public static final int START_CASH = 1_500;
    public static final int SALARY = 200;
    public static final int CUSTOMS_FEE = 100;
    public static final int ESCAPE_FEE = 50;
    public static final int FLIGHT_FEE = 50;

    /** 살 때 같이 지을 수 있는 최대 단계(상점). */
    public static final int MAX_PURCHASE_LEVEL = 2;
    /** 풍등탑. */
    public static final int MAX_LEVEL = 3;

    /** 소용돌이에서 이만큼 실패하면 다음 차례에 저절로 풀린다(§7.2). */
    public static final int MAX_FAILED_ESCAPES = 2;

    public static final int SAIL_REPAIR = 80;
    public static final int STAR_CHEST = 120;
    public static final int INSPECTION_PER_LEVEL = 20;
    public static final int ISLAND_FEAST = 30;
    public static final int WIND_SHARE = 100;
    public static final int HEADWIND_STEPS = 3;
    public static final int GUST_STEPS = 2;

    private Economy() {
    }

    /** 인원별 라운드 상한(§4.3) — 2인 30·3인 24·4인 20. */
    public static int roundLimit(int seatCount) {
        return switch (seatCount) {
            case 2 -> 30;
            case 3 -> 24;
            case 4 -> 20;
            default -> throw new IllegalArgumentException("Cloud Route seats 2~4: " + seatCount);
        };
    }

    /** 십분의 일 단위 금액을 10G 단위로 반올림(5 는 올림, §6.3·§6.4). */
    public static int round10FromTenths(long tenths) {
        return (int) ((tenths + 50) / 100 * 10);
    }

    /** 통행료(§6.3) — 기본 × (독점 1.5) × (축제 2), 10G 단위 반올림. */
    public static int toll(Tile tile, int level, boolean monopoly, boolean festival) {
        long multiplierTenths = (monopoly ? 15 : 10) * (festival ? 2L : 1L);
        return round10FromTenths(tile.tolls().get(level) * multiplierTenths);
    }

    /** 인수가(§6.4) — (땅값 + 단계 × 건설비) × 1.5, 10G 단위 반올림. */
    public static int takeoverPrice(Tile tile, int level) {
        return round10FromTenths((tile.price() + (long) level * tile.buildCost()) * 15L);
    }

    /** 총자산에 들어가는 그 땅의 정가(§12.2). */
    public static int assetValue(Tile tile, int level) {
        return tile.price() + level * tile.buildCost();
    }
}
