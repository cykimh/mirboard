package com.mirboard.domain.game.yacht.score;

/**
 * 점수표 12칸 (`docs/rules-yacht.md` §5). 선언 순서가 점수표 순서이고, 시간 초과 자동 기록의 동점 판정(위쪽 칸 우선,
 * §6)도 이 순서를 쓴다 — 상수 순서를 바꾸지 말 것.
 */
public enum Category {
    ONES(1),
    TWOS(2),
    THREES(3),
    FOURS(4),
    FIVES(5),
    SIXES(6),
    CHOICE(0),
    FOUR_OF_A_KIND(0),
    FULL_HOUSE(0),
    SMALL_STRAIGHT(0),
    LARGE_STRAIGHT(0),
    YACHT(0);

    private final int face;

    Category(int face) {
        this.face = face;
    }

    /** 상단 6칸(1~6)이면 그 눈, 아니면 0. */
    public int face() {
        return face;
    }

    public boolean upper() {
        return face > 0;
    }
}
