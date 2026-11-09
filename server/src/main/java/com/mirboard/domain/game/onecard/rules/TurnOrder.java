package com.mirboard.domain.game.onecard.rules;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import java.util.function.IntPredicate;

/** 다음 차례 (`docs/rules-onecard.md` §8.2). 살아 있는 좌석만 센다(§2). */
public final class TurnOrder {

    private TurnOrder() {
    }

    /** from 다음으로 direction 쪽에 있는 살아 있는 좌석. from 자신은 한 바퀴 돌아 마지막에 본다. */
    public static int nextAlive(int seatCount, IntPredicate alive, int from, int direction) {
        for (int step = 1; step <= seatCount; step++) {
            int seat = Math.floorMod(from + direction * step, seatCount);
            if (alive.test(seat)) {
                return seat;
            }
        }
        throw new IllegalStateException("no live seat");
    }

    /**
     * 방금 낸 카드 다음의 차례 (§8.2). Q 는 방향을 이미 뒤집은 뒤의 {@code direction} 을 받는다.
     * 살아 있는 사람이 2명이면 J 는 상대를 건너뛰어 저절로 "한 번 더"가 된다 (§8.1). Q 는 2명이어도 상대에게 넘긴다(D-173).
     */
    public static int afterPlay(int seatCount, IntPredicate alive, int seat, int direction, PlayingCard card) {
        if (card.isExtraTurn()) {
            return seat;
        }
        if (card.isSkip()) {
            int skipped = nextAlive(seatCount, alive, seat, direction);
            return nextAlive(seatCount, alive, skipped, direction);
        }
        return nextAlive(seatCount, alive, seat, direction);
    }
}
