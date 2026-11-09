package com.mirboard.domain.game.cloudroute.board;

import java.util.Arrays;
import java.util.List;

/** 바람 편지 10장 — 한 종류에 한 장씩 (`docs/rules-cloudroute.md` §9). */
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
    GUST;

    /** 섞기 전의 한 벌. */
    public static List<Letter> fullDeck() {
        return Arrays.asList(values().clone());
    }
}
