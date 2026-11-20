package com.mirboard.domain.game.starsquare.bot;

/**
 * 봇 강도(`docs/rules-starsquare.md` §10) — 방 맵 옵션 {@code BOT_LEVEL} 의 값(S2 에서 연결). 완성 시각 = 띠 기준 시간 × 이 배수 × 흔들림.
 *
 * @param factor 배수 [수치] — 클수록 느리다. 보통 = 사람 기준점 근처(D-285)
 */
public enum BotLevel {
    EASY(1.5),
    NORMAL(1.0),
    HARD(0.7);

    private final double factor;

    BotLevel(double factor) {
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }

    /** 방 옵션 값 → 강도. 모르는 값·null 은 기본 보통. */
    public static BotLevel fromOption(String value) {
        if (value == null) {
            return NORMAL;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return NORMAL;
        }
    }
}
