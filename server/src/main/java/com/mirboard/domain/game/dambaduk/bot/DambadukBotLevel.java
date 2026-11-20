package com.mirboard.domain.game.dambaduk.bot;

/**
 * 담바둑 봇 난이도 (D-277). 방 설정 {@code BOT_LEVEL}(게임 중립, D-176)의 값과 이름이 같다 — 방의 봇 전원에 같은 값. 오목의
 * {@code BotLevel} 을 가져다 쓰지 않는 것은 게임 패키지끼리 의존하지 않으려는 것이다.
 */
public enum DambadukBotLevel {
    EASY,
    NORMAL,
    HARD;

    /** 방 설정 값 해석 — 모르는 값·빈 값은 보통. */
    public static DambadukBotLevel parse(String value) {
        if (value == null) {
            return NORMAL;
        }
        for (DambadukBotLevel l : values()) {
            if (l.name().equals(value)) {
                return l;
            }
        }
        return NORMAL;
    }
}
