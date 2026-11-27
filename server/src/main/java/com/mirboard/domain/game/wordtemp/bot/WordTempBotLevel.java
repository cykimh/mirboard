package com.mirboard.domain.game.wordtemp.bot;

/**
 * 봇 강도(`docs/rules-wordtemp.md` §11, D-320 에서 다시 정함). 출제는 "기준을 넘기는 힌트를 얼마나 빨리 내는가"로, 추리는 "아는 은행·온도
 * 읽기 잡음·차수당 맞힐 확률 상한"으로 가른다. 힌트가 전부 공개되고 차수가 5번이라 상한을 v1(10·30·50%)보다 낮췄다 — 차수마다 다시
 * 기회가 오므로 한 출제 차례에 맞힐 확률은 1 − (1 − 상한)^차수 로 쌓인다.
 *
 * @param warmMin   출제 — 기준을 넘기는 힌트 앞에 내는 낮은 힌트 개수(최소~최대). 많을수록 단서가 많아 추리자에게 쉽다
 * @param warmMax   〃
 * @param knownPct  추리 — 아는 은행의 비율(%) — 정답이 그 안에 드는지도 운
 * @param noise     추리 — 온도 읽기 잡음(±)
 * @param hitCapPct 추리 — 차수마다 "가장 그럴듯한 답"을 그대로 내는 확률(%) = 맞힐 확률의 상한. 걸리면 두 번째 후보나 패스
 */
public enum WordTempBotLevel {
    EASY(2, 3, 30, 15, 6),
    NORMAL(1, 2, 60, 8, 14),
    HARD(0, 1, 100, 3, 25);

    /** 기준을 넘기는 힌트의 목표 띠 — 기준 ~ 기준+5°(넘기되 너무 뜨겁지 않게). */
    public static final int PASS_BAND = 5;

    private final int warmMin;
    private final int warmMax;
    private final int knownPct;
    private final int noise;
    private final int hitCapPct;

    WordTempBotLevel(int warmMin, int warmMax, int knownPct, int noise, int hitCapPct) {
        this.warmMin = warmMin;
        this.warmMax = warmMax;
        this.knownPct = knownPct;
        this.noise = noise;
        this.hitCapPct = hitCapPct;
    }

    public int warmMin() {
        return warmMin;
    }

    public int warmMax() {
        return warmMax;
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
