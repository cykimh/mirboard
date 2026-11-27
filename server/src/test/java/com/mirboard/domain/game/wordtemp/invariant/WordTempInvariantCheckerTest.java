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
        return game().selectAll(0).hint("여름").hint("갈매기").guess(1, "항구");
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
            for (String extra : List.of("가", "나", "다", "라", "마", "바", "사", "아", "자", "차")) {
                d.hints.add(new Hint(extra, 1, HintStatus.SCORED, 10, HintSource.TABLE, 0));
            }
        })).anyMatch(v -> v.contains("has 11 hints"));
        assertThat(broken(g, d -> d.attempt = 5)).noneMatch(v -> v.contains("attempt out of range")); // 5차까지 있다
        assertThat(broken(g, d -> d.attempt = 6)).anyMatch(v -> v.contains("attempt out of range"));
        assertThat(broken(g, d -> {
            d.hints.add(new Hint("가", 1, HintStatus.PENDING, -1, HintSource.LIVE, 5));
            d.hints.add(new Hint("나", 1, HintStatus.PENDING, -1, HintSource.LIVE, 6));
        })).anyMatch(v -> v.contains("2 pending hints"));
        assertThat(broken(g, d -> d.hints.add(new Hint("여름", 1, HintStatus.SCORED, 58, HintSource.TABLE, 0))))
                .anyMatch(v -> v.contains("duplicate hint"));
        assertThat(broken(g, d -> d.liveRequests = 61)).anyMatch(v -> v.contains("live requests"));
    }

    @Test
    void catches_an_attempt_summary_that_does_not_match_its_hints() {
        String mismatch = "attempt summary of attempt 1 does not match its hints";
        // 가장 높은 힌트가 아니다
        assertThat(broken(guessing(), d -> d.revealed.set(0, new Revealed(1, "여름", 58, 70, false))))
                .anyMatch(v -> v.contains(mismatch));
        // 기준 온도가 그 차수의 값이 아니다
        assertThat(broken(guessing(), d -> d.revealed.set(0, new Revealed(1, "갈매기", 71, 50, true))))
                .anyMatch(v -> v.contains(mismatch));
        // 넘겼는데 못 넘겼다고 적었다 / 힌트가 있는데 없다고 적었다
        assertThat(broken(guessing(), d -> d.revealed.set(0, new Revealed(1, "갈매기", 71, 70, false))))
                .anyMatch(v -> v.contains(mismatch));
        assertThat(broken(guessing(), d -> d.revealed.set(0, new Revealed(1, null, -1, 70, false))))
                .anyMatch(v -> v.contains(mismatch));
        assertThat(broken(guessing(), d -> d.revealed.clear())).anyMatch(v -> v.contains("revealed 0 hints"));
        // 힌트 0개로 끝난 차수의 요약(낱말 없음·−1·미달)은 정상이다
        Game empty = game().selectAll(0).timer();
        assertThat(empty.state.revealed()).containsExactly(new Revealed(1, null, -1, 70, false));
        assertThat(WordTempInvariantChecker.violations(empty.state, empty.engine.config())).isEmpty();
    }

    @Test
    void catches_a_second_hint_at_or_above_the_floor_in_one_attempt() {
        // 기준을 넘긴 힌트가 나오면 그 자리에서 단계가 끝난다 — 한 차수에 둘일 수 없다(D-320)
        assertThat(broken(guessing(), d -> d.hints.add(new Hint("파도", 1, HintStatus.SCORED, 93, HintSource.TABLE, 0))))
                .anyMatch(v -> v.contains("attempt 1 has 2 hints at or above the floor"));
        // 다음 차수의 기준으로는 따로 센다
        Game second = game().selectAll(0).hint("배").passAll().timer().hint("섬");
        assertThat(second.state.attempt()).isEqualTo(2);
        assertThat(WordTempInvariantChecker.violations(second.state, second.engine.config())).isEmpty();
    }

    @Test
    void catches_a_setter_gain_that_is_not_the_attempt_number_times_the_round() {
        Game g = game().selectAll(0).hint("배").passAll(); // 1차 전원 오답 → +1
        assertThat(g.state.attempts().get(0).setterGain()).isEqualTo(1);
        assertThat(broken(g, d -> {
            d.attempts.set(0, new WordTempState.AttemptResult(1, d.attempts.get(0).guesses(), 3));
            d.seats.set(0, d.seats.get(0).plus(2, 0));
        })).anyMatch(v -> v.contains("attempt 1 setter gain 3"));
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
