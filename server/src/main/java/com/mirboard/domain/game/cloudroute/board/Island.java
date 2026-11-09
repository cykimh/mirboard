package com.mirboard.domain.game.cloudroute.board;

/** 하늘섬 8곳 — 섬마다 땅 2칸 (`docs/rules-cloudroute.md` §2). 같은 섬 두 칸을 다 가지면 독점(§6.3). */
public enum Island {
    MOSS,
    SHELL,
    MUSHROOM,
    WINDMILL,
    GLASS,
    STAR_APPLE,
    LAVA,
    CROWN
}
