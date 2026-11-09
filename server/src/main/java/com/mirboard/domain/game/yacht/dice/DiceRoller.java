package com.mirboard.domain.game.yacht.dice;

import java.util.Random;

/**
 * 주사위 하나의 눈을 정한다(1~6). 순수 엔진은 이것만 받는다 — 테스트는 정해진 수열을, 포트 어댑터(S3)는 {@link Random}
 * 을 주입한다. 눈은 굴리는 순간에만 정해지고 상태에 미래 눈·시드를 두지 않는다(`docs/rules-yacht.md` §1, D-182).
 */
@FunctionalInterface
public interface DiceRoller {

    int roll();

    static DiceRoller from(Random random) {
        return () -> random.nextInt(Dice.FACES) + 1;
    }

    /** 주어진 눈을 차례로 돌려준다 — 단위 테스트용. 다 쓰면 예외. */
    static DiceRoller sequence(int... faces) {
        int[] cursor = {0};
        return () -> {
            if (cursor[0] >= faces.length) {
                throw new IllegalStateException("DiceRoller.sequence exhausted after " + faces.length);
            }
            return faces[cursor[0]++];
        };
    }
}
