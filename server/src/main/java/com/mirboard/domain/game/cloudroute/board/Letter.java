package com.mirboard.domain.game.cloudroute.board;

import java.util.Arrays;
import java.util.List;

/** 바람 편지 12장 — 한 종류에 한 장씩 (`docs/rules-cloudroute.md` §9, D-216 에서 10 → 12). */
public enum Letter {
    TAILWIND,
    SAIL_REPAIR,
    STAR_CHEST,
    HEADWIND,
    WHIRLPOOL_ALERT,
    BUOY,
    INSPECTION,
    ISLAND_FEAST,
    WIND_SHARE,
    GUST,
    /** D-216 — 내 땅 하나(상점 미만)에 1단계 무료 건설. */
    LANTERN_GIFT,
    /** D-216 — 바람 정류장으로 가서 무료로 날 칸을 고른다. */
    FREE_FLIGHT;

    /** 섞기 전의 한 벌. */
    public static List<Letter> fullDeck() {
        return Arrays.asList(values().clone());
    }
}
