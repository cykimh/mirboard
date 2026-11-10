package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static com.mirboard.domain.game.yacht.YachtFixtures.timedEngine;
import static com.mirboard.domain.game.yacht.YachtFixtures.withTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.YachtState;
import org.junit.jupiter.api.Test;

/** `docs/rules-yacht.md` §6 — 차례 전체 제한과 자동 처리. */
class YachtEngineTimeoutTest {

    private final YachtEngine engine = timedEngine(2, 45);

    @Test
    void remaining_counts_from_the_turn_start_and_ignores_holds_and_rolls() {
        YachtState s = engine.start(T0).newState();
        assertThat(engine.remainingMillis(s, T0).getAsLong()).isEqualTo(45_000);

        s = engine.apply(s, 0, YachtAction.Roll.plain(), DiceRoller.sequence(1, 2, 3, 4, 6), T0 + 10_000).newState();
        // 홀드를 계속 바꿔도 남은 시간은 처음부터 다시 세지 않는다 — 시각이 흐른 만큼만 준다
        for (int i = 0; i < 20; i++) {
            HoldMask next = i % 2 == 0 ? HoldMask.of(true, false, false, false, false) : HoldMask.NONE;
            s = engine.apply(s, 0, new YachtAction.Hold(next), () -> 1, T0 + 10_000 + i * 1_000).newState();
        }
        assertThat(s.turn().startedAt()).isEqualTo(T0);
        assertThat(engine.remainingMillis(s, T0 + 30_000).getAsLong()).isEqualTo(15_000);
        assertThat(engine.remainingMillis(s, T0 + 50_000).getAsLong()).isEqualTo(-5_000);
    }

    @Test
    void the_next_turn_gets_a_fresh_clock() {
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(1, 1, 1, 1, 1), HoldMask.NONE, 1);
        s = engine.apply(s, 0, new YachtAction.Score(Category.YACHT), () -> 1, T0 + 40_000).newState();
        assertThat(engine.remainingMillis(s, T0 + 40_000).getAsLong()).isEqualTo(45_000);
    }

    @Test
    void no_limit_means_no_clock() {
        YachtEngine unlimited = timedEngine(2, 0);
        assertThat(unlimited.remainingMillis(unlimited.start(T0).newState(), T0)).isEmpty();
    }

    @Test
    void expiring_before_any_roll_rolls_once_and_scores_the_best_category() {
        YachtState s = engine.start(T0).newState();
        YachtEngine.Result r = engine.expire(s, DiceRoller.sequence(2, 3, 4, 5, 6), T0 + 45_000);

        assertThat(r.events()).hasSize(3);
        assertThat(r.events().get(0)).isInstanceOf(YachtEvent.DiceRolled.class);
        assertThat(r.events().get(1)).isEqualTo(
                new YachtEvent.Scored(0, Category.LARGE_STRAIGHT, 30, 0, 0, 30, true));
        assertThat(r.events().get(2)).isEqualTo(new YachtEvent.TurnChanged(1, 1, 45));
        assertThat(r.newState().actionCount()).isZero();
    }

    @Test
    void expiring_after_rolls_scores_the_current_dice_with_ties_going_up() {
        // 1 2 3 5 6 — 초이스 17 이 가장 높다
        YachtState s = withTurn(engine.start(T0).newState(), Dice.of(1, 2, 3, 5, 6), HoldMask.NONE, 2);
        YachtEngine.Result r = engine.expire(s, () -> {
            throw new AssertionError("must not roll");
        }, T0 + 45_000);
        assertThat(r.events().get(0)).isEqualTo(new YachtEvent.Scored(0, Category.CHOICE, 17, 0, 0, 17, true));

        // 1 1 1 3 3 — 초이스 9 = 풀 하우스 9 동점 → 위 칸인 초이스
        YachtState t = withTurn(engine.start(T0).newState(), Dice.of(1, 1, 1, 3, 3), HoldMask.NONE, 3);
        YachtEvent scored = engine.expire(t, () -> 1, T0).events().get(0);
        assertThat(((YachtEvent.Scored) scored).category()).isEqualTo(Category.CHOICE);
    }

    @Test
    void expiring_a_finished_match_is_an_error() {
        YachtState s = withTurn(YachtFixtures.openState(1, 12, 0), Dice.of(1, 1, 1, 1, 1), HoldMask.NONE, 1);
        YachtEngine solo = timedEngine(1, 45);
        YachtState ended = solo.expire(s, () -> 1, T0).newState();
        assertThat(ended.ended()).isTrue();
        assertThatThrownBy(() -> solo.expire(ended, () -> 1, T0)).isInstanceOf(IllegalStateException.class);
        assertThat(solo.remainingMillis(ended, T0)).isEmpty();
    }

    @Test
    void the_fallback_action_rolls_first_then_scores_the_best() {
        YachtState fresh = engine.start(T0).newState();
        assertThat(engine.fallbackAction(fresh, 0)).isEqualTo(YachtAction.Roll.plain());
        // 4 4 4 4 2 — 포 카드 18 = 초이스 18 동점 → 위 칸인 초이스
        YachtState rolled = withTurn(fresh, Dice.of(4, 4, 4, 4, 2), HoldMask.NONE, 1);
        assertThat(engine.fallbackAction(rolled, 0)).isEqualTo(new YachtAction.Score(Category.CHOICE));
        // 초이스를 쓴 뒤에는 포 카드
        YachtState choiceUsed = YachtFixtures.withCard(rolled, 0,
                com.mirboard.domain.game.yacht.score.Scorecard.EMPTY.with(Category.CHOICE, 10));
        assertThat(engine.fallbackAction(choiceUsed, 0)).isEqualTo(new YachtAction.Score(Category.FOUR_OF_A_KIND));
    }
}
