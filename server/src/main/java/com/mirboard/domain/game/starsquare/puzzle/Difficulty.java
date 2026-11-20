package com.mirboard.domain.game.starsquare.puzzle;

/**
 * 난이도(`docs/rules-starsquare.md` §4.1) — 방 맵 옵션 {@code DIFFICULTY} 의 값(S2 에서 연결). 퍼즐 번호마다 띠를 정한다.
 */
public enum Difficulty {
    /** 3×3 과 4×4 번갈아 — 1·3·5번째 3×3, 2·4번째 4×4. */
    EASY,
    /** 4×4. */
    NORMAL,
    /** 5×5. */
    HARD;

    /** 퍼즐 번호(0 부터)의 띠. */
    public Band bandFor(int index) {
        return switch (this) {
            case EASY -> index % 2 == 0 ? Band.THREE_EASY : Band.FOUR_EASY;
            case NORMAL -> Band.FOUR_NORMAL;
            case HARD -> Band.FIVE_HARD;
        };
    }

    /**
     * 대기실 퍼즐 제한 "자동"(턴 제한 0)일 때의 퍼즐 제한(초) [수치] — 사람 풀이 시간 추정(D-285)의 약 3배를 분 단위로 반올림:
     * 3×3 8분·4×4 쉬움 4분 → 쉬움 8분, 4×4 보통 7분, 5×5 어려움 20분.
     */
    public int autoLimitSeconds() {
        return switch (this) {
            case EASY -> 480;
            case NORMAL -> 420;
            case HARD -> 1_200;
        };
    }

    /** 방 옵션 값 → 난이도. 모르는 값·null 은 기본 보통. */
    public static Difficulty fromOption(String value) {
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
