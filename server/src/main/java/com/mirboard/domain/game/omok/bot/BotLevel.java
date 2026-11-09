package com.mirboard.domain.game.omok.bot;

/** 봇 난이도 (D-179). 방 설정 {@code BOT_LEVEL} 의 값과 이름이 같다. 방의 봇 전원에 같은 값. */
public enum BotLevel {
    EASY,
    NORMAL,
    HARD;

    /** 방 설정 값 해석 — 모르는 값·빈 값은 보통. */
    public static BotLevel parse(String value) {
        if (value == null) {
            return NORMAL;
        }
        for (BotLevel l : values()) {
            if (l.name().equals(value)) {
                return l;
            }
        }
        return NORMAL;
    }
}
