package com.mirboard.domain.game.yacht.dice;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.List;

/**
 * 주사위 5개의 고정 여부 (`docs/rules-yacht.md` §1·§4). 위치 i 가 비트 i 다.
 *
 * <p>JSON 으로는 불리언 5개 배열이다({@code [true,false,false,true,false]}) — 클라 액션의 {@code held} 와 같은 모양.
 * 길이가 5 가 아니면 역직렬화가 실패해 인프라가 {@code INVALID_ACTION} 으로 돌려준다.
 */
public record HoldMask(int bits) {

    public static final HoldMask NONE = new HoldMask(0);
    public static final HoldMask ALL = new HoldMask((1 << Dice.COUNT) - 1);

    public HoldMask {
        if (bits < 0 || bits > (1 << Dice.COUNT) - 1) {
            throw new IllegalArgumentException("Hold mask out of range: " + bits);
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static HoldMask of(List<Boolean> held) {
        if (held == null || held.size() != Dice.COUNT) {
            throw new IllegalArgumentException("held needs " + Dice.COUNT + " booleans: " + held);
        }
        int bits = 0;
        for (int i = 0; i < Dice.COUNT; i++) {
            if (Boolean.TRUE.equals(held.get(i))) {
                bits |= 1 << i;
            }
        }
        return new HoldMask(bits);
    }

    public static HoldMask of(boolean a, boolean b, boolean c, boolean d, boolean e) {
        return of(List.of(a, b, c, d, e));
    }

    @JsonValue
    public List<Boolean> asList() {
        List<Boolean> out = new ArrayList<>(Dice.COUNT);
        for (int i = 0; i < Dice.COUNT; i++) {
            out.add(isHeld(i));
        }
        return List.copyOf(out);
    }

    public boolean isHeld(int position) {
        return (bits & (1 << position)) != 0;
    }

    public boolean none() {
        return bits == 0;
    }

    public boolean all() {
        return bits == ALL.bits;
    }

    /** 가능한 32개 마스크 전부(비트 오름차순). */
    public static List<HoldMask> every() {
        List<HoldMask> out = new ArrayList<>(1 << Dice.COUNT);
        for (int b = 0; b < 1 << Dice.COUNT; b++) {
            out.add(new HoldMask(b));
        }
        return List.copyOf(out);
    }
}
