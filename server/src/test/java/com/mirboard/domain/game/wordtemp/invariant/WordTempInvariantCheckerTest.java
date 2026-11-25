package com.mirboard.domain.game.wordtemp.invariant;

import static com.mirboard.domain.game.wordtemp.WordTempFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.wordtemp.WordTempFixtures.Game;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.BotMove;
import com.mirboard.domain.game.wordtemp.state.WordTempState.BotMoveKind;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Draft;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Guess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintSource;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 불변식 검사기가 깨진 상태를 실제로 잡는다(`docs/rules-wordtemp.md` §17) — 시뮬레이션이 "위반 0"이라고 말할 때 그 말이 뜻이 있게.
 */
class WordTempInvariantCheckerTest {

    private static List<String> broken(Game g, Consumer<Draft> damage) {
        Draft d = g.state.draft();
        damage.accept(d);
        return WordTempInvariantChecker.violations(d.build(), g.engine.config());
    }

    private static Game guessing() {
        return game().selectAll(0).hint("여름").hint("갈매기").endHints().guess(1, "항구");
    }

    @Test
    void healthy_states_have_no_violations() {
        assertThat(WordTempInvariantChecker.violations(game().state, game().engine.config())).isEmpty();
        Game g = guessing();
        assertThat(WordTempInvariantChecker.violations(g.state, g.engine.config())).isEmpty();
        g.passAll().until(Phase.TURN_END);
        assertThat(WordTempInvariantChecker.violations(g.state, g.engine.config())).isEmpty();
    }

    @Test
    void catches_points_that_do_not_come_from_the_recorded_attempts() {
        assertThat(broken(guessing(), d -> d.seats.set(2, d.seats.get(2).plus(0, 1))))
                .anyMatch(v -> v.contains("seat 2 points"));
        assertThat(broken(guessing(), d -> d.seats.set(0, d.seats.get(0).plus(7, 0))))
                .anyMatch(v -> v.contains("seat 0 points"));
    }

    @Test
    void catches_too_many_hints_or_attempts_and_a_second_pending_hint() {
        Game g = game().selectAll(0).hint("여름");
        assertThat(broken(g, d -> {
            for (String extra : List.of("가", "나", "다", "라", "마")) {
                d.hints.add(new Hint(extra, 1, HintStatus.SCORED, 10, HintSource.TABLE, 0));
            }
        })).anyMatch(v -> v.contains("has 6 hints"));
        assertThat(broken(g, d -> d.attempt = 4)).anyMatch(v -> v.contains("attempt out of range"));
        assertThat(broken(g, d -> {
            d.hints.add(new Hint("가", 1, HintStatus.PENDING, -1, HintSource.LIVE, 5));
            d.hints.add(new Hint("나", 1, HintStatus.PENDING, -1, HintSource.LIVE, 6));
        })).anyMatch(v -> v.contains("2 pending hints"));
        assertThat(broken(g, d -> d.hints.add(new Hint("여름", 1, HintStatus.SCORED, 58, HintSource.TABLE, 0))))
                .anyMatch(v -> v.contains("duplicate hint"));
        assertThat(broken(g, d -> d.liveRequests = 61)).anyMatch(v -> v.contains("live requests"));
    }

    @Test
    void catches_a_revealed_hint_that_is_not_the_hottest_or_has_the_wrong_floor() {
        assertThat(broken(guessing(), d -> d.revealed.set(0, new Revealed(1, "여름", 58, 50, true))))
                .anyMatch(v -> v.contains("not the best one"));
        assertThat(broken(guessing(), d -> d.revealed.set(0, new Revealed(1, "갈매기", 71, 40, true))))
                .anyMatch(v -> v.contains("wrong floor"));
        assertThat(broken(guessing(), d -> d.revealed.clear())).anyMatch(v -> v.contains("revealed 0 hints"));
    }

    @Test
    void catches_guesses_from_the_setter_an_out_seat_or_outside_the_guess_phase() {
        assertThat(broken(guessing(), d -> d.guesses.add(new Guess(0, "바다"))))
                .anyMatch(v -> v.contains("bad guess from seat 0"));
        assertThat(broken(guessing(), d -> d.guesses.add(new Guess(1, "바다"))))
                .anyMatch(v -> v.contains("bad guess from seat 1"));
        assertThat(broken(guessing(), d -> d.seats.set(1, d.seats.get(1).out())))
                .anyMatch(v -> v.contains("bad guess from seat 1"));
        assertThat(broken(guessing(), d -> d.phase = Phase.TURN_END))
                .anyMatch(v -> v.contains("unrevealed guesses"));
    }

    @Test
    void catches_a_wrong_answer_a_broken_order_and_stray_bot_moves() {
        Game g = guessing();
        assertThat(broken(g, d -> d.answer = d.seats.get(1).candidates().get(0)))
                .anyMatch(v -> v.contains("not the setter's chosen candidate"));
        assertThat(broken(g, d -> d.order = List.of(0, 1, 1, 3))).anyMatch(v -> v.contains("not a permutation"));
        assertThat(broken(g, d -> d.botPlan.add(new BotMove(2, 5, BotMoveKind.GUESS))))
                .anyMatch(v -> v.contains("non-bot"));
        assertThat(broken(g, d -> d.seats.set(3, d.seats.get(3).chose(-1).nextRound())))
                .isEmpty(); // 아직 출제하지 않은 좌석의 고름은 불변식 대상이 아니다
        assertThat(broken(g, d -> d.phase = Phase.FINISHED)).anyMatch(v -> v.contains("result/phase mismatch"));
        assertThat(broken(g, d -> {
            d.seats.set(1, d.seats.get(1).out());
            d.seats.set(2, d.seats.get(2).out());
            d.seats.set(3, d.seats.get(3).out());
            d.guesses.clear();
        })).anyMatch(v -> v.contains("match goes on"));
    }

    @Test
    void check_throws_with_every_violation_listed() {
        Game g = guessing();
        Draft d = g.state.draft();
        d.attempt = 9;
        WordTempState bad = d.build();
        assertThatThrownBy(() -> WordTempInvariantChecker.check(bad, g.engine.config()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("attempt out of range");
    }
}
