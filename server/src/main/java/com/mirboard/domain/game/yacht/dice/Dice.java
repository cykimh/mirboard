package com.mirboard.domain.game.yacht.dice;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * 주사위 5개의 눈 (`docs/rules-yacht.md` §1). 위치 0~4 로 구분하고 각 눈은 1~6 이다.
 *
 * <p>JSON 으로는 정수 5개 배열 하나다({@code [3,3,4,1,5]}) — 공개 뷰·이벤트·Redis 상태가 같은 모양을 쓴다.
 */
public record Dice(List<Integer> values) {

    public static final int COUNT = 5;
    public static final int FACES = 6;

    public Dice {
        values = List.copyOf(values);
        if (values.size() != COUNT) {
            throw new IllegalArgumentException("Dice needs " + COUNT + " values: " + values);
        }
        for (int v : values) {
            if (v < 1 || v > FACES) {
                throw new IllegalArgumentException("Die face out of range: " + values);
            }
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Dice of(List<Integer> values) {
        return new Dice(values);
    }

    public static Dice of(int a, int b, int c, int d, int e) {
        return new Dice(List.of(a, b, c, d, e));
    }

    @JsonValue
    public List<Integer> asList() {
        return values;
    }

    public int face(int position) {
        return values.get(position);
    }

    /** 눈별 개수 — 인덱스 1~6 을 쓴다(0 은 비워 둔다). */
    public int[] counts() {
        int[] counts = new int[FACES + 1];
        for (int v : values) {
            counts[v]++;
        }
        return counts;
    }

    public int sum() {
        int sum = 0;
        for (int v : values) {
            sum += v;
        }
        return sum;
    }

    /** 홀드하지 않은 위치만 새로 굴린 주사위. */
    public Dice reroll(HoldMask held, DiceRoller roller) {
        Integer[] next = values.toArray(Integer[]::new);
        for (int i = 0; i < COUNT; i++) {
            if (!held.isHeld(i)) {
                next[i] = roller.roll();
            }
        }
        return new Dice(List.of(next));
    }

    /** 첫 굴림 — 5개 전부. */
    public static Dice rollAll(DiceRoller roller) {
        Integer[] next = new Integer[COUNT];
        for (int i = 0; i < COUNT; i++) {
            next[i] = roller.roll();
        }
        return new Dice(List.of(next));
    }
}
