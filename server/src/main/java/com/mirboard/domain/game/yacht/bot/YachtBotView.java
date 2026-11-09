package com.mirboard.domain.game.yacht.bot;

import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.state.YachtState;

/**
 * 봇 정책이 보는 것 (D-118·D-119 원칙 — 공개 정보만). 요트는 숨길 정보가 없지만 정책이 상태 전체·남의 점수표에 기대지 않게
 * 자기 차례에 필요한 것만 담는다.
 *
 * @param dice  지금 눈, 첫 굴림 전이면 null
 * @param rolls 굴린 횟수 0~3
 */
public record YachtBotView(Scorecard card, Dice dice, HoldMask held, int rolls) {

    public static YachtBotView of(YachtState state, int seat) {
        return new YachtBotView(state.card(seat), state.turn().dice(), state.turn().held(), state.turn().rolls());
    }
}
