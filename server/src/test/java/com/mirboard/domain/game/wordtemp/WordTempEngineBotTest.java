package com.mirboard.domain.game.wordtemp;

import static com.mirboard.domain.game.wordtemp.WordTempFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.WordTempFixtures.Game;
import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState.BotMoveKind;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintSource;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-wordtemp.md` §11 — 봇은 엔진 타이머가 움직인다(대기 좌석에 없다). 표 안의 낱말만 쓰고, 사람을 기다리게 하지 않는다.
 */
class WordTempEngineBotTest {

    /** 좌석 0 만 사람이고 좌석 1(봇)이 첫 출제자인 판. */
    private static Game botSets() {
        Game g = new Game(WordTempConfig.of(4, Set.of(1, 2, 3), 1, 90,
                com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel.NORMAL), 1);
        return g.act(0, new WordTempAction.SelectWord(1, 0));
    }

    @Test
    void a_bot_setter_gives_public_table_hints_four_to_nine_seconds_apart_until_one_meets_the_floor() {
        Game g = botSets();
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        assertThat(g.state.setterSeat()).isEqualTo(1);
        assertThat(g.engine.pendingSeats(g.state)).isEmpty(); // 봇은 대기 좌석이 아니다
        assertThat(g.state.botPlan()).hasSize(1);
        assertThat(g.state.botPlan().get(0).kind()).isEqualTo(BotMoveKind.HINT);
        long wait = g.engine.timer(g.state, g.now).orElseThrow();
        assertThat(wait).isBetween(4_000L, 9_000L);
        // 사람은 봇 대신 힌트를 낼 수 없다
        assertThat(g.rejected(0, new WordTempAction.SubmitHint(1, 1, "생일"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);

        long started = g.now;
        int hints = 0;
        while (g.state.phase() == Phase.HINT) {
            long before = g.now;
            g.timer();
            assertThat(g.now - before).isBetween(4_000L, 9_000L);
            hints++;
            // D-320 — 봇의 힌트도 낼 때마다 공개 이벤트로 나간다(낱말·온도)
            WordTempEvent.HintScored scored = g.event(WordTempEvent.HintScored.class);
            assertThat(scored.seat()).isEqualTo(1);
            assertThat(scored.count()).isEqualTo(hints);
            assertThat(g.event(WordTempEvent.HintCount.class).count()).isEqualTo(hints);
            assertThat(g.events).noneMatch(WordTempEvent::isPrivate);
            assertThat(g.state.phase() == Phase.GUESS).isEqualTo(scored.temp() >= 70);
        }
        assertThat(g.now - started).isLessThan(60_000);
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.state.hints()).isNotEmpty().allMatch(h -> h.status() == HintStatus.SCORED
                && h.source() == HintSource.TABLE);
        assertThat(g.state.liveRequests()).isZero();
        // 보통: 낮은 힌트 1~2개 뒤에 기준을 넘기는 힌트 하나(고정 표의 세 후보 모두 70° 이상 이웃이 있다)
        List<Hint> given = g.state.hints();
        Hint last = given.get(given.size() - 1);
        assertThat(last.temp()).isGreaterThanOrEqualTo(70);
        assertThat(given.subList(0, given.size() - 1)).allMatch(h -> h.temp() < 70 && h.temp() >= 40);
        assertThat(g.state.hintCount()).isBetween(2, 3);
        assertThat(g.state.revealed().get(0)).isEqualTo(new Revealed(1, last.text(), last.temp(), 70, true));
    }

    @Test
    void a_bot_setter_that_knows_no_hint_above_the_floor_gives_its_hottest_one_and_stops_without_the_points() {
        // 기준 95° 하나짜리 판 — 고정 표의 봇 후보(케이크·눈사람·수영)는 모두 95° 이상 이웃이 없다
        WordTempConfig config = WordTempConfig.of(4, Set.of(1, 2, 3), 1, 90,
                com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel.NORMAL)
                .withTuning(WordTempTuning.DEFAULT.withFloors(95));
        Game g = new Game(config, 1);
        g.act(0, new WordTempAction.SelectWord(1, 0));
        g.until(Phase.GUESS);
        String answer = g.state.answer().word();
        int hottest = WordTempFixtures.table().neighbors(answer).stream()
                .filter(n -> !n.hint().contains(answer)).mapToInt(n -> n.temp()).max().orElseThrow();
        Revealed summary = g.state.revealed().get(0);
        assertThat(summary.temp()).isEqualTo(hottest).isLessThan(95);
        assertThat(summary.floorMet()).isFalse();
        List<Hint> given = g.state.hints();
        assertThat(given.get(given.size() - 1).temp()).as("마지막이 아는 것 중 가장 높은 힌트").isEqualTo(hottest);
        assertThat(g.state.hintCount()).isLessThan(10); // 계획을 다 내면 스스로 그만둔다
        g.guess(0, "").timer();
        // 전원 오답이어도 기준을 못 넘겼으니 출제자 0점, 차수가 하나뿐이라 차례가 끝난다
        assertThat(g.state.seat(1).setterPoints()).isZero();
        assertThat(g.state.phase()).isEqualTo(Phase.TURN_END);
    }

    @Test
    void bot_guessers_pass_without_any_hint_and_keep_using_the_hints_of_earlier_attempts() {
        Game g = new Game(WordTempConfig.of(4, Set.of(1, 2, 3), 1, 90,
                com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel.HARD), 0);
        g.act(0, new WordTempAction.SelectWord(1, 0));
        g.timer().until(Phase.ATTEMPT_RESULT); // 1차: 힌트 0개 — 단서가 없으니 봇은 모두 패스
        assertThat(g.state.attempts().get(0).guesses()).hasSize(3).allMatch(guess -> guess.pass());
        g.timer().hint("여름").hint("소금").endHints().until(Phase.ATTEMPT_RESULT); // 2차: 기준 아래 둘
        int answered = 0;
        while (g.state.phase() != Phase.TURN_END) {
            // 3차부터는 새 힌트가 없다 — 그래도 2차의 공개 힌트가 단서로 남아 있다(D-320: 그 차례의 확정 힌트 전부)
            g.timer();
            if (g.state.phase() == Phase.ATTEMPT_RESULT && g.state.attempt() >= 3) {
                var last = g.state.attempts().get(g.state.attempts().size() - 1);
                answered += (int) last.guesses().stream().filter(guess -> !guess.pass()).count();
            }
        }
        assertThat(answered).isPositive();
        assertThat(g.state.seat(0).setterPoints()).isZero(); // 한 번도 기준을 못 넘겼다
    }

    @Test
    void bot_guessers_answer_the_moment_every_human_has() {
        Game g = botSets().until(Phase.GUESS);
        assertThat(g.engine.pendingSeats(g.state)).containsExactly(0);
        assertThat(g.state.botPlan()).extracting(m -> m.seat()).containsExactlyInAnyOrder(2, 3);
        assertThat(g.state.botPlan()).allMatch(m -> m.at() - g.now >= 5_000 && m.at() - g.now <= 20_000);
        g.after(1_000).guess(0, "");
        assertThat(g.state.phase()).isEqualTo(Phase.ATTEMPT_RESULT);
        assertThat(g.events.stream().filter(WordTempEvent.GuessSubmitted.class::isInstance)
                .map(e -> ((WordTempEvent.GuessSubmitted) e).seat())).containsExactly(0, 2, 3);
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).guesses()).hasSize(3);
        assertThat(g.state.botPlan()).isEmpty();
    }

    @Test
    void bots_answer_on_their_own_clock_while_a_human_is_still_thinking() {
        Game g = botSets().until(Phase.GUESS);
        g.timer();
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.events).hasSize(1).allMatch(WordTempEvent.GuessSubmitted.class::isInstance);
        g.timer();
        assertThat(g.state.guesses()).hasSize(2);
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS); // 사람을 기다린다
        assertThat(g.engine.timer(g.state, g.now).orElseThrow())
                .isEqualTo(g.engine.deadline(g.state) - g.now);
        g.timer(); // 30초 마감 → 사람은 패스
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).guesses().get(0).pass()).isTrue();
    }

    @Test
    void a_guess_phase_with_only_bots_to_answer_flows_quickly() {
        Game g = game(3, Set.of(1, 2), 1);
        g.act(0, new WordTempAction.SelectWord(1, 0)); // 좌석 0(사람)이 출제, 추리자는 봇뿐
        g.hint("갈매기");
        assertThat(g.engine.pendingSeats(g.state)).isEmpty();
        long wait = g.engine.timer(g.state, g.now).orElseThrow();
        assertThat(wait).isLessThanOrEqualTo(1_500);
        g.timer();
        assertThat(g.state.phase()).isEqualTo(Phase.ATTEMPT_RESULT); // 한 봇이 내면 나머지도 같이 낸다
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).guesses()).hasSize(2);
    }

    @Test
    void a_bot_never_repeats_an_answer_that_was_already_revealed_wrong_and_never_guesses_its_own_candidates() {
        for (int first = 0; first < 4; first++) {
            Game g = new Game(WordTempConfig.of(4, Set.of(1, 2, 3), 2, 60,
                    com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel.HARD), first);
            int guard = 0;
            while (!g.state.ended() && guard++ < 2_000) {
                if (!g.engine.pendingSeats(g.state).isEmpty() && g.state.phase() == Phase.WORD_SELECT) {
                    g.act(0, new WordTempAction.SelectWord(g.state.round(), g.state.round() - 1));
                } else {
                    g.timer();
                }
                var wrongSoFar = new java.util.HashSet<String>();
                for (var attempt : g.state.attempts()) {
                    for (var guess : attempt.guesses()) {
                        if (guess.pass() || guess.seat() == 0) {
                            continue;
                        }
                        assertThat(g.state.seat(guess.seat()).candidates())
                                .noneMatch(c -> c.word().equals(guess.text()));
                        if (!guess.correct()) {
                            assertThat(wrongSoFar.add(guess.seat() + ":" + guess.text())).isTrue();
                        }
                    }
                }
            }
            assertThat(g.state.ended()).isTrue();
            assertThat(g.state.history()).hasSize(8);
        }
    }
}
