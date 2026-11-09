package com.mirboard.domain.game.yacht.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.yacht.YachtEngine;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 설계 §6.5 — 공개 정보만, 결정적, 늘 합법. */
class YachtBotPolicyTest {

    private static YachtBotView view(Scorecard card, Dice dice, HoldMask held, int rolls) {
        return new YachtBotView(card, dice, held, rolls);
    }

    @Test
    void rolls_first_and_scores_after_the_third_roll() {
        assertThat(YachtBotPolicy.choose(view(Scorecard.EMPTY, null, HoldMask.NONE, 0))).isEqualTo(YachtAction.Roll.plain());
        assertThat(YachtBotPolicy.choose(view(Scorecard.EMPTY, Dice.of(1, 2, 3, 4, 6), HoldMask.NONE, 3)))
                .isInstanceOf(YachtAction.Score.class);
    }

    @Test
    void takes_a_yacht_and_a_large_straight_at_once() {
        assertThat(YachtBotPolicy.choose(view(Scorecard.EMPTY, Dice.of(4, 4, 4, 4, 4), HoldMask.NONE, 1)))
                .isEqualTo(new YachtAction.Score(Category.YACHT));
        assertThat(YachtBotPolicy.choose(view(Scorecard.EMPTY, Dice.of(2, 3, 4, 5, 6), HoldMask.NONE, 1)))
                .isEqualTo(new YachtAction.Score(Category.LARGE_STRAIGHT));
    }

    @Test
    void holds_four_of_a_kind_before_rerolling_the_odd_die() {
        YachtAction first = YachtBotPolicy.choose(view(Scorecard.EMPTY, Dice.of(6, 6, 1, 6, 6), HoldMask.NONE, 1));
        assertThat(first).isEqualTo(new YachtAction.Hold(HoldMask.of(true, true, false, true, true)));
        // 홀드한 뒤에는 바꾸지 않고 굴린다
        YachtAction second = YachtBotPolicy.choose(view(Scorecard.EMPTY, Dice.of(6, 6, 1, 6, 6),
                HoldMask.of(true, true, false, true, true), 1));
        assertThat(second).isEqualTo(YachtAction.Roll.plain());
    }

    @Test
    void does_not_throw_away_the_yacht_box_when_something_smaller_fits() {
        Scorecard card = Scorecard.EMPTY;
        for (Category c : Category.values()) {
            if (c != Category.YACHT && c != Category.ONES) {
                card = card.with(c, 0);
            }
        }
        // 1 이 하나 — 요트(0) 대신 1 칸(1)에 쓴다
        assertThat(YachtBotPolicy.choose(view(card, Dice.of(1, 2, 3, 5, 6), HoldMask.NONE, 3)))
                .isEqualTo(new YachtAction.Score(Category.ONES));
    }

    @Test
    void the_policy_is_deterministic_and_always_legal_through_whole_matches() {
        YachtEngine engine = new YachtEngine(new GameContext("p", List.of(1L, 2L), 0, 0, List.of(0, 1), Map.of(), 0));
        for (long seed = 0; seed < 40; seed++) {
            DiceRoller roller = DiceRoller.from(new Random(seed));
            YachtState s = engine.start(0).newState();
            int steps = 0;
            while (!s.ended()) {
                int seat = s.turnSeat();
                YachtBotView v = YachtBotView.of(s, seat);
                YachtAction a = YachtBotPolicy.choose(v);
                assertThat(YachtBotPolicy.choose(v)).isEqualTo(a);
                assertThat(engine.legalActions(s, seat)).contains(a);
                s = engine.apply(s, seat, a, roller, 0).newState();
                steps++;
            }
            assertThat(s.result().reason().name()).isEqualTo("FINISHED");
            // 차례당 액션이 적다 — 홀드를 오가며 맴돌지 않는다
            assertThat(steps).isLessThan(2 * 12 * 7);
        }
    }
}
