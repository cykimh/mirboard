package com.mirboard.domain.game.wordtemp.bot;

/**
 * 봇 강도(`docs/rules-wordtemp.md` §11 표) — 방 맵 옵션 {@code BOT_LEVEL} 의 값(S2 에서 연결). 수치는 전부 [수치] — S2 봇 평가로 고친다.
 *
 * @param bandLow     출제 — 공개될 힌트의 목표 띠 아래(절대 온도). {@code floorBand} 면 "그 차수 최저 온도 + bandLow"
 * @param bandHigh    출제 — 목표 띠 위
 * @param floorBand   목표 띠를 차수별 최저 온도에 붙여 잡는가(어려움 — 최저 바로 위)
 * @param minHints    출제 — 차수당 힌트 개수(최소~최대)
 * @param maxHints    〃
 * @param knownPct    추리 — 아는 은행의 비율(%) — 정답이 그 안에 드는지도 운
 * @param noise       추리 — 온도 읽기 잡음(±)
 * @param hitCapPct   추리 — 차수마다 "가장 그럴듯한 답"을 그대로 내는 확률(%) = 맞힐 확률의 상한. 걸리면 두 번째 후보나 패스
 */
public enum WordTempBotLevel {
    EASY(72, 92, false, 1, 2, 30, 15, 10),
    NORMAL(65, 85, false, 2, 3, 60, 8, 30),
    HARD(0, 12, true, 2, 4, 100, 3, 50);

    private final int bandLow;
    private final int bandHigh;
    private final boolean floorBand;
    private final int minHints;
    private final int maxHints;
    private final int knownPct;
    private final int noise;
    private final int hitCapPct;

    WordTempBotLevel(int bandLow, int bandHigh, boolean floorBand, int minHints, int maxHints, int knownPct,
                     int noise, int hitCapPct) {
        this.bandLow = bandLow;
        this.bandHigh = bandHigh;
        this.floorBand = floorBand;
        this.minHints = minHints;
        this.maxHints = maxHints;
        this.knownPct = knownPct;
        this.noise = noise;
        this.hitCapPct = hitCapPct;
    }

    /** 그 차수(최저 온도 {@code floor})의 목표 띠 아래. */
    public int bandLow(int floor) {
        return floorBand ? floor + bandLow : bandLow;
    }

    public int bandHigh(int floor) {
        return floorBand ? floor + bandHigh : bandHigh;
    }

    public int minHints() {
        return minHints;
    }

    public int maxHints() {
        return maxHints;
    }

    public int knownPct() {
        return knownPct;
    }

    public int noise() {
        return noise;
    }

    public int hitCapPct() {
        return hitCapPct;
    }

    /** 방 옵션 값 → 강도. 모르는 값·null 은 기본 보통. */
    public static WordTempBotLevel fromOption(String value) {
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
