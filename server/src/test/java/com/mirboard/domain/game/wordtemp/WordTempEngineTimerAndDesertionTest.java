package com.mirboard.domain.game.wordtemp;

import static com.mirboard.domain.game.wordtemp.WordTempFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.WordTempEngine.Desertion.Outcome;
import com.mirboard.domain.game.wordtemp.WordTempFixtures.Game;
import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Standing;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-wordtemp.md` §9(시간 — 엔진 타이머와 멱등 {@code TICK})·§10(탈주·강퇴).
 */
class WordTempEngineTimerAndDesertionTest {

    // ---- §9 ----

    /** 사람만의 판에서 단계마다 한 번씩 멈춘 상태들. */
    private static List<Supplier<Game>> everyPhase() {
        List<Supplier<Game>> games = new ArrayList<>();
        games.add(() -> game().act(0, new WordTempAction.SelectWord(1, 0)));                    // WORD_SELECT
        games.add(() -> game().selectAll(0).hint("여름"));                                       // HINT (힌트 있음)
        games.add(() -> game().selectAll(0));                                                    // HINT (힌트 0개)
        games.add(() -> game().selectAll(0).hint("갈매기").endHints().guess(1, "바다"));          // GUESS
        games.add(() -> game().selectAll(0).hint("갈매기").endHints().passAll());                 // ATTEMPT_RESULT → 2차
        games.add(() -> game().selectAll(0).hint("갈매기").endHints().guess(1, "바다").passAll()); // ATTEMPT_RESULT → 끝
        games.add(() -> game().selectAll(0).timer());                                            // TURN_END
        return games;
    }

    @Test
    void the_phase_start_is_in_the_state_so_rearming_does_not_restart_the_clock() {
        Game g = game().selectAll(0);
        assertThat(g.engine.timer(g.state, g.now)).hasValue(90_000);
        assertThat(g.engine.timer(g.state, g.now + 30_000)).hasValue(60_000);
        assertThat(g.engine.timer(g.state, g.now + 200_000)).hasValue(0);
        // 힌트를 내도 단계 마감은 밀리지 않는다
        g.after(40_000).hint("여름");
        assertThat(g.engine.timer(g.state, g.now)).hasValue(50_000);
        assertThat(g.engine.deadline(g.state)).isEqualTo(g.state.phaseStartedAt() + 90_000);
    }

    @Test
    void an_early_timer_fire_changes_nothing() {
        for (Supplier<Game> at : everyPhase()) {
            Game g = at.get();
            var r = g.engine.onTimer(g.state, g.now + 1);
            assertThat(r.newState()).isSameAs(g.state);
            assertThat(r.events()).isEmpty();
        }
    }

    @Test
    void a_tick_after_the_deadline_does_exactly_what_the_timer_does() {
        for (Supplier<Game> at : everyPhase()) {
            Game g = at.get();
            WordTempState s = g.state;
            long due = g.engine.deadline(s);
            var byTimer = g.engine.onTimer(s, due);
            var byTick = g.engine.apply(s, 2, new WordTempAction.Tick(s.turnNo(), s.attempt(), s.phase()), due);
            assertThat(byTick.newState()).as("phase %s", s.phase()).isEqualTo(byTimer.newState());
            assertThat(byTick.events()).isEqualTo(byTimer.events());
            assertThat(byTimer.newState()).isNotEqualTo(s);
        }
    }

    @Test
    void a_stale_or_early_tick_changes_nothing() {
        for (Supplier<Game> at : everyPhase()) {
            Game g = at.get();
            WordTempState s = g.state;
            long due = g.engine.deadline(s);
            // 아직 마감 전
            assertThat(g.engine.apply(s, 0, new WordTempAction.Tick(s.turnNo(), s.attempt(), s.phase()), due - 1)
                    .newState()).isSameAs(s);
            // 다른 단계·차례·차수를 가리키는 낡은 TICK(힌트를 낼 때마다 밀려 다시 걸린 데드라인)
            Phase other = s.phase() == Phase.GUESS ? Phase.HINT : Phase.GUESS;
            assertThat(g.engine.apply(s, 0, new WordTempAction.Tick(s.turnNo(), s.attempt(), other), due + 5_000)
                    .newState()).isSameAs(s);
            assertThat(g.engine.apply(s, 0, new WordTempAction.Tick(s.turnNo() + 1, s.attempt(), s.phase()),
                    due + 5_000).newState()).isSameAs(s);
            assertThat(g.engine.apply(s, 0, new WordTempAction.Tick(s.turnNo(), s.attempt() + 1, s.phase()),
                    due + 5_000).events()).isEmpty();
        }
    }

    @Test
    void pending_seats_are_the_living_humans_with_something_left_to_do() {
        Game g = game(4, Set.of(2), 1);
        assertThat(g.engine.pendingSeats(g.state)).containsExactly(0, 1, 3);
        g.selectAll(0);
        assertThat(g.engine.pendingSeats(g.state)).containsExactly(0);
        g.hint("갈매기").endHints();
        assertThat(g.engine.pendingSeats(g.state)).containsExactly(1, 3); // 봇 2 는 엔진 타이머가 움직인다
        g.guess(1, "").guess(3, "");
        assertThat(g.state.phase()).isEqualTo(Phase.ATTEMPT_RESULT);
        assertThat(g.engine.pendingSeats(g.state)).isEmpty();
    }

    // ---- §10 ----

    @Test
    void a_setter_leaving_mid_turn_ends_the_turn_on_the_spot_and_keeps_points_already_earned() {
        Game g = game().selectAll(0).hint("갈매기").endHints().passAll().timer(); // 1차 +1, 2차 힌트 중
        g.hint("소금").endHints().guess(1, "바다");                                // 2차 추리 중 — 한 명은 이미 정답을 냈다
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        g.desert(0);
        assertThat(g.events.get(0)).isEqualTo(new WordTempEvent.SeatOut(0));
        WordTempEvent.TurnEnded ended = g.event(WordTempEvent.TurnEnded.class);
        assertThat(ended.reason()).isEqualTo(TurnEndReason.SETTER_OUT);
        assertThat(ended.answer()).isEqualTo("바다");
        assertThat(g.has(WordTempEvent.GuessesRevealed.class)).isFalse(); // 진행 중이던 차수의 점수는 아무도 받지 않는다
        assertThat(g.state.totals()).containsExactly(1, 0, 0, 0);
        assertThat(g.state.phase()).isEqualTo(Phase.TURN_END);
        g.timer();
        assertThat(g.state.setterSeat()).isEqualTo(1);
        assertThat(g.state.history().get(0).setterGain()).isEqualTo(1);
    }

    @Test
    void a_guesser_leaving_counts_as_a_pass_and_closes_the_phase_if_everyone_else_is_in() {
        Game g = game().selectAll(0).hint("갈매기").endHints().guess(1, "항구").guess(2, "바다");
        Game left = g.desert(3);
        assertThat(left.state.phase()).isEqualTo(Phase.ATTEMPT_RESULT);
        WordTempEvent.GuessesRevealed r = left.event(WordTempEvent.GuessesRevealed.class);
        assertThat(r.guesses().stream().map(WordTempEvent.RevealedGuess::seat)).containsExactly(1, 2);
        assertThat(left.state.totals()).containsExactly(0, 0, 1, 0);
    }

    @Test
    void a_guesser_who_already_answered_and_then_leaves_does_not_score() {
        Game g = game().selectAll(0).hint("갈매기").endHints().guess(1, "바다").desert(1).passAll();
        assertThat(g.state.totals()).containsExactly(1, 0, 0, 0); // 남은 둘이 패스 → 전원 오답, 출제자 +1
    }

    @Test
    void leaving_during_word_select_lets_the_rest_go_on() {
        Game g = game();
        g.act(0, new WordTempAction.SelectWord(1, 0)).act(1, new WordTempAction.SelectWord(1, 0))
                .act(2, new WordTempAction.SelectWord(1, 0));
        assertThat(g.state.phase()).isEqualTo(Phase.WORD_SELECT);
        g.desert(3);
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        assertThat(g.state.seat(3).alive()).isFalse();
        assertThat(g.state.seatCount()).isEqualTo(4); // 좌석 번호는 그대로(D-122)
    }

    @Test
    void out_seats_are_skipped_as_setters_and_guessers() {
        Game g = game().selectAll(0);
        g.desert(1);
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        g.hint("갈매기").endHints();
        assertThat(g.event(WordTempEvent.GuessPhaseStarted.class).seats()).containsExactly(2, 3);
        g.passAll().until(Phase.TURN_END).timer();
        assertThat(g.state.setterSeat()).isEqualTo(2); // 좌석 1 을 건너뛴다
        assertThat(g.rejected(1, new WordTempAction.SubmitGuess(g.state.turnNo(), 1, "피아노")))
                .isEqualTo(RejectionReason.NOT_YOUR_PHASE);
    }

    @Test
    void a_second_request_for_the_same_seat_is_not_applicable() {
        Game g = game().selectAll(0);
        var first = g.engine.desert(g.state, 2, g.now);
        assertThat(first.outcome()).isEqualTo(Outcome.MATCH_CONTINUES);
        var again = g.engine.desert(first.newState(), 2, g.now);
        assertThat(again.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(again.newState()).isSameAs(first.newState());
        assertThat(g.engine.desert(g.state, 9, g.now).outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void two_left_play_on_one_on_one_and_one_left_ends_the_match() {
        Game g = game(3, Set.of(), 1).selectAll(0);
        g.hint("갈매기").endHints().guess(1, "바다").guess(2, "");
        g.desert(2);
        assertThat(g.state.ended()).isFalse();
        g.until(Phase.TURN_END).timer();
        assertThat(g.state.setterSeat()).isEqualTo(1);
        var last = g.engine.desert(g.state, 0, g.now);
        assertThat(last.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        var result = last.newState().result();
        assertThat(result.endReason()).isEqualTo(EndReason.LAST_SEAT);
        assertThat(result.draw()).isFalse();
        assertThat(result.standings().stream().map(Standing::rank)).containsExactly(2, 1, 2);
        assertThat(result.standings().get(0).out()).isTrue();
        assertThat(last.events().get(last.events().size() - 1)).isInstanceOf(WordTempEvent.MatchEnded.class);
    }

    @Test
    void a_setter_whose_leaving_also_ends_the_match_still_has_the_answer_revealed_first() {
        Game g = game(3, Set.of(), 1).selectAll(0).hint("갈매기").endHints().passAll().timer(); // 1차 +1, 2차 힌트 중
        g.desert(2);
        assertThat(g.state.ended()).isFalse();
        g.desert(0);
        assertThat(g.events).hasSize(3);
        assertThat(g.events.get(0)).isEqualTo(new WordTempEvent.SeatOut(0));
        assertThat(((WordTempEvent.TurnEnded) g.events.get(1)).reason()).isEqualTo(TurnEndReason.SETTER_OUT);
        assertThat(((WordTempEvent.TurnEnded) g.events.get(1)).answer()).isEqualTo("바다");
        assertThat(g.events.get(2)).isInstanceOf(WordTempEvent.MatchEnded.class);
        assertThat(g.state.history()).hasSize(1);
        assertThat(g.state.result().endReason()).isEqualTo(EndReason.LAST_SEAT);
        assertThat(g.state.result().standings().get(0).setterPoints()).isEqualTo(1); // 받은 점수는 유지, 순위는 최하위
        assertThat(g.state.result().standings().get(0).rank()).isEqualTo(2);
    }

    @Test
    void a_guesser_whose_leaving_ends_the_match_mid_turn_leaves_the_answer_unrevealed() {
        Game g = game(3, Set.of(), 1).selectAll(0).hint("갈매기").endHints().passAll().timer();
        g.desert(2).desert(1);
        assertThat(g.events).hasSize(2);
        assertThat(g.events.get(1)).isInstanceOf(WordTempEvent.MatchEnded.class);
        assertThat(g.state.history()).isEmpty();
        assertThat(g.state.result().standings().get(0).total()).isEqualTo(1);
        assertThat(g.state.result().standings().get(0).rank()).isEqualTo(1);
    }

    @Test
    void the_match_ends_when_no_human_is_left() {
        Game g = game(4, Set.of(1, 2), 1).selectAll(0);
        g.desert(0);
        assertThat(g.state.ended()).isFalse();
        var d = g.engine.desert(g.state, 3, g.now);
        assertThat(d.outcome()).isEqualTo(Outcome.MATCH_ENDED);
        assertThat(d.newState().result().endReason()).isEqualTo(EndReason.NO_HUMANS);
        assertThat(d.newState().botPlan()).isEmpty();
    }
}
