package com.mirboard.domain.game.yacht.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;

/**
 * 지금 차례의 주사위 (`docs/rules-yacht.md` §4).
 *
 * @param dice      지금 눈. 첫 굴림 전이면 null
 * @param held      홀드. 굴린 횟수 0 이면 모두 풀림
 * @param rolls     굴린 횟수 0~3
 * @param startedAt 차례 시작 시각(epoch ms) — 차례 전체 제한(§6, D-183)의 기준. 순수 엔진은 시각을 인자로 받는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnState(Dice dice, HoldMask held, int rolls, long startedAt) {

    public static final int MAX_ROLLS = 3;

    public TurnState {
        held = held == null ? HoldMask.NONE : held;
    }

    public static TurnState fresh(long startedAt) {
        return new TurnState(null, HoldMask.NONE, 0, startedAt);
    }

    /** 끝난 매치의 빈 차례. */
    public static TurnState none() {
        return new TurnState(null, HoldMask.NONE, 0, 0L);
    }
}
