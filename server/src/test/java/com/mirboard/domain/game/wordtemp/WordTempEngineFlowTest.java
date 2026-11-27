package com.mirboard.domain.game.wordtemp;

import static com.mirboard.domain.game.wordtemp.WordTempFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.WordTempFixtures.Game;
import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Standing;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempViews;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-wordtemp.md` §3(흐름)·§3.4·§4(점수 표)·§5(거절)·§8(끝)·§14(예시) — 사람만의 판을 손으로 몬다.
 * 룰 v2(D-320): 차수 5번·기준 70/75/80/85/90·차수당 힌트 10개, 힌트는 낼 때마다 공개, 기준을 넘긴 힌트가 나오면 그 자리에서 추리로.
 */
class WordTempEngineFlowTest {

    // ---- §3.1 낱말 고르기 ----

    @Test
    void starts_in_word_select_and_tells_each_human_only_their_own_candidates() {
        Game g = game(4, Set.of(3), 1);
        assertThat(g.state.phase()).isEqualTo(Phase.WORD_SELECT);
        assertThat(g.event(WordTempEvent.WordSelectStarted.class).remainingMs()).isEqualTo(20_000);
        List<WordTempEvent.WordCandidates> mine = g.events.stream()
                .filter(WordTempEvent.WordCandidates.class::isInstance)
                .map(WordTempEvent.WordCandidates.class::cast).toList();
        assertThat(mine.stream().map(WordTempEvent.WordCandidates::seat)).containsExactly(0, 1, 2); // 봇 3 은 없다
        assertThat(mine.get(0).candidates().stream().map(WordTempEvent.CandidateInfo::word))
                .containsExactly("바다", "사과", "휴대폰");
        assertThat(mine.get(0).privateSeat()).isZero();
        assertThat(mine.get(0).sequenced()).isFalse();
        assertThat(g.engine.pendingSeats(g.state)).containsExactly(0, 1, 2);
    }

    @Test
    void the_turn_starts_as_soon_as_every_human_has_chosen_and_bots_pick_at_random() {
        Game g = game(4, Set.of(3), 1);
        g.act(1, new WordTempAction.SelectWord(1, 2));
        assertThat(g.event(WordTempEvent.WordSelected.class).seat()).isEqualTo(1);
        assertThat(g.rejected(1, new WordTempAction.SelectWord(1, 0))).isEqualTo(RejectionReason.ALREADY_CHOSEN);
        assertThat(g.rejected(0, new WordTempAction.SelectWord(1, 3))).isEqualTo(RejectionReason.BAD_CHOICE);
        assertThat(g.rejected(0, new WordTempAction.SelectWord(2, 0))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(3, new WordTempAction.SelectWord(1, 0))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        g.act(0, new WordTempAction.SelectWord(1, 0));
        g.act(2, new WordTempAction.SelectWord(1, 1));
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        assertThat(g.state.seat(3).chosen()).isBetween(0, 2);
        assertThat(g.event(WordTempEvent.TurnStarted.class)).isEqualTo(new WordTempEvent.TurnStarted(1, 1, 0));
        assertThat(g.event(WordTempEvent.HintPhaseStarted.class).remainingMs()).isEqualTo(90_000);
        assertThat(g.state.answer().word()).isEqualTo("바다");
        assertThat(g.state.seat(1).candidates().get(g.state.seat(1).chosen()).word()).isEqualTo("수영");
    }

    @Test
    void whoever_did_not_choose_in_twenty_seconds_gets_a_random_remaining_candidate() {
        Game g = game();
        g.act(0, new WordTempAction.SelectWord(1, 1));
        assertThat(g.engine.timer(g.state, g.now)).hasValue(20_000);
        g.timer();
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        assertThat(g.state.answer().word()).isEqualTo("사과");
        for (int s = 1; s < 4; s++) {
            assertThat(g.state.seat(s).chosen()).isBetween(0, 2);
        }
    }

    // ---- §14 예시 한 출제 차례 (룰 v2, D-320) ----

    @Test
    void plays_an_example_turn_with_public_hints_a_passing_hint_and_a_floor_not_met() {
        Game g = game().selectAll(0);
        g.live = true;

        g.hint("여름");
        assertThat(g.events).containsExactly(new WordTempEvent.HintScored(0, "여름", 58, 1),
                new WordTempEvent.HintCount(1, 10));
        assertThat(g.state.phase()).isEqualTo(Phase.HINT); // 58 < 70 — 계속 낸다
        g.hint(" 갈매기! ");                                // 71 ≥ 70 — 그 자리에서 추리로
        assertThat(g.events).containsExactly(new WordTempEvent.HintScored(0, "갈매기", 71, 2),
                new WordTempEvent.HintCount(2, 10),
                new WordTempEvent.HintRevealed(1, "갈매기", 71, 70, true),
                new WordTempEvent.GuessPhaseStarted(1, 30_000, List.of(1, 2, 3)));

        g.guess(1, "항구").guess(2, "해변");
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.events).containsExactly(new WordTempEvent.GuessAccepted(2, "해변"),
                new WordTempEvent.GuessSubmitted(2));
        g.guess(3, "");
        WordTempEvent.GuessesRevealed first = g.event(WordTempEvent.GuessesRevealed.class);
        // D-319 — 결과 보기·차례 끝도 남은 시간을 싣는다(화면 시계)
        assertThat(first.remainingMs()).isEqualTo(g.engine.config().tuning().resultMs());
        assertThat(first.setterGain()).isEqualTo(1);
        assertThat(first.guesses()).containsExactly(
                new WordTempEvent.RevealedGuess(1, "항구", false, false, false),
                new WordTempEvent.RevealedGuess(2, "해변", false, false, false),
                new WordTempEvent.RevealedGuess(3, null, true, false, false));
        assertThat(first.scores().get(0)).isEqualTo(new WordTempEvent.ScoreChange(0, 1, 1));
        assertThat(g.state.phase()).isEqualTo(Phase.ATTEMPT_RESULT);

        g.timer(); // 결과 보기 5초 → 2차 힌트(기준 75°)
        assertThat(g.state.attempt()).isEqualTo(2);
        assertThat(g.event(WordTempEvent.HintPhaseStarted.class)).isEqualTo(
                new WordTempEvent.HintPhaseStarted(1, 2, 90_000));
        g.hint("짠맛"); // 표에 없음 → 채점 중
        assertThat(g.event(WordTempEvent.HintPending.class).text()).isEqualTo("짠맛");
        g.after(2_000).score("짠맛", new WordTempEngine.LiveScore.Scored(64));
        assertThat(g.state.phase()).isEqualTo(Phase.HINT); // 64 < 75
        g.endHints();                                      // 기준을 못 넘긴 채 [힌트 끝]
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(2, "짠맛", 64, 75, false));
        assertThat(g.state.revealed()).hasSize(2);

        g.guess(1, "바다요").guess(2, "소금").guess(3, "바다");
        WordTempEvent.GuessesRevealed second = g.event(WordTempEvent.GuessesRevealed.class);
        assertThat(second.setterGain()).isZero();
        assertThat(second.guesses().stream().map(WordTempEvent.RevealedGuess::correct))
                .containsExactly(true, false, true);
        assertThat(g.state.totals()).containsExactly(1, 1, 0, 1);

        g.timer(); // 결과 보기 → 차례 끝
        assertThat(g.state.phase()).isEqualTo(Phase.TURN_END);
        WordTempEvent.TurnEnded ended = g.event(WordTempEvent.TurnEnded.class);
        assertThat(ended.remainingMs()).isEqualTo(g.engine.config().tuning().turnEndMs());
        assertThat(ended.answer()).isEqualTo("바다");
        assertThat(ended.reason()).isEqualTo(TurnEndReason.SOLVED);
        // revealed = 그 차수의 기준을 넘긴 힌트였는가
        assertThat(ended.hints()).containsExactly(new WordTempEvent.HintInfo(1, "여름", 58, false),
                new WordTempEvent.HintInfo(1, "갈매기", 71, true), new WordTempEvent.HintInfo(2, "짠맛", 64, false));
        assertThat(ended.totals()).containsExactly(1, 1, 0, 1);

        assertThat(g.engine.timer(g.state, g.now)).hasValue(6_000);
        g.timer(); // 6초 → 다음 출제자
        assertThat(g.event(WordTempEvent.TurnStarted.class)).isEqualTo(new WordTempEvent.TurnStarted(2, 1, 1));
        assertThat(g.state.answer().word()).isEqualTo("케이크");
        assertThat(g.state.hints()).isEmpty();
        assertThat(g.state.history()).hasSize(1);
        assertThat(g.state.history().get(0).solvers()).containsExactly(1, 3);
        assertThat(g.state.history().get(0).setterGain()).isEqualTo(1);
    }

    // ---- §3.2 힌트 단계 ----

    @Test
    void every_hint_is_a_public_sequenced_event_the_moment_it_is_scored() {
        Game g = game().selectAll(0);
        g.live = true;
        List<WordTempEvent> seen = new java.util.ArrayList<>(g.hint("여름").events);
        seen.addAll(g.hint("짠맛").events);
        seen.addAll(g.score("짠맛", new WordTempEngine.LiveScore.NotANoun()).events);
        seen.addAll(g.hint("배").events);
        assertThat(seen).anyMatch(WordTempEvent.HintScored.class::isInstance)
                .anyMatch(WordTempEvent.HintPending.class::isInstance)
                .anyMatch(WordTempEvent.HintVoid.class::isInstance)
                .anyMatch(WordTempEvent.HintRevealed.class::isInstance);
        for (WordTempEvent e : seen) {
            assertThat(e.isPrivate()).as(e.envelopeType()).isFalse();
            assertThat(e.privateSeat()).isEqualTo(-1);
            assertThat(e.sequenced()).isTrue();
            assertThat(e).isNotInstanceOf(WordTempEvent.Private.class);
        }
    }

    @Test
    void a_hint_at_or_above_the_floor_ends_the_hint_phase_on_the_spot_and_is_the_passing_hint() {
        Game g = game().selectAll(0).hint("여름").hint("물");
        assertThat(g.state.phase()).isEqualTo(Phase.HINT); // 66 — 아직
        g.hint("배");                                      // 70 = 기준
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(1, "배", 70, 70, true));
        assertThat(g.state.revealed().get(0).floorMet()).isTrue();
        assertThat(g.state.hintCount()).isEqualTo(3);
        // 더 낼 수 없다 — 단계가 끝났다
        assertThat(g.rejected(0, new WordTempAction.SubmitHint(1, 1, "파도"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(0, new WordTempAction.EndHints(1, 1))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        // 아주 뜨거운 힌트도 똑같이 "넘긴 힌트"일 뿐이다(96° 조기 종료 규칙은 없어졌다)
        Game hot = game().selectAll(0).hint("해양");
        assertThat(hot.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(1, "해양", 97, 70, true));
    }

    @Test
    void ending_hints_early_summarises_the_attempt_with_its_hottest_hint_and_the_earlier_one_on_a_tie() {
        Game g = game().selectAll(0).hint("여름").hint("물").hint("소금").endHints();
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(1, "물", 66, 70, false));
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);

        // 같은 온도 둘 — 먼저 낸 것이 요약에 남는다
        InMemorySimilarityTable same = WordTempFixtures.table().put("바다", "등대", 62);
        WordTempEngine e = new WordTempEngine(WordTempConfig.of(4, Set.of(), 1, 90, WordTempBotLevel.NORMAL), same);
        var s = e.start(WordTempFixtures.candidates(4, 1), WordTempFixtures.seedStartingAt(4, 0), 0).newState();
        for (int seat = 0; seat < 4; seat++) {
            s = e.apply(s, seat, new WordTempAction.SelectWord(1, 0), 1).newState();
        }
        s = e.apply(s, 0, new WordTempAction.SubmitHint(1, 1, "등대"), 2).newState();
        s = e.apply(s, 0, new WordTempAction.SubmitHint(1, 1, "소금"), 3).newState();
        s = e.apply(s, 0, new WordTempAction.EndHints(1, 1), 4).newState();
        assertThat(s.revealed().get(0).text()).isEqualTo("등대");
    }

    @Test
    void the_tenth_hint_without_reaching_the_floor_goes_to_guessing_and_the_setter_gets_nothing() {
        Game g = game().selectAll(0);
        for (String low : WordTempFixtures.LOWS.subList(0, 9)) {
            g.hint(low);
        }
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        assertThat(g.state.hintCount()).isEqualTo(9);
        g.hint(WordTempFixtures.LOWS.get(9));
        assertThat(g.event(WordTempEvent.HintCount.class)).isEqualTo(new WordTempEvent.HintCount(10, 10));
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(1, "조개", 68, 70, false));
        g.passAll();
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).setterGain()).isZero();
        assertThat(g.state.totals()).containsOnly(0);
        g.timer();
        assertThat(g.state.attempt()).isEqualTo(2); // 그래도 다음 차수로 간다
        assertThat(g.state.hintCount()).isZero();
    }

    @Test
    void hints_run_out_of_time_below_the_floor_and_guessing_still_happens() {
        Game g = game().selectAll(0).hint("여름");
        assertThat(g.engine.timer(g.state, g.now)).hasValue(90_000);
        g.timer();
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(1, "여름", 58, 70, false));
        g.guess(1, "바다").passAll(); // 추리자는 그대로 점수를 받는다
        assertThat(g.state.totals()).containsExactly(0, 1, 0, 0);
    }

    @Test
    void zero_hints_at_the_deadline_still_goes_to_guessing_and_the_setter_gets_nothing() {
        Game g = game().selectAll(0).timer();
        assertThat(g.events).containsExactly(new WordTempEvent.HintRevealed(1, null, -1, 70, false),
                new WordTempEvent.GuessPhaseStarted(1, 30_000, List.of(1, 2, 3)));
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.has(WordTempEvent.TurnEnded.class)).isFalse();
        g.passAll();
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).setterGain()).isZero();
        assertThat(g.state.totals()).containsOnly(0);
        g.timer();
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        assertThat(g.state.attempt()).isEqualTo(2);
    }

    @Test
    void zero_hints_in_a_later_attempt_keeps_the_points_already_earned() {
        Game g = game().selectAll(0).hint("갈매기").passAll().timer();
        assertThat(g.state.attempt()).isEqualTo(2);
        assertThat(g.total(0)).isEqualTo(1);
        g.timer(); // 2차 힌트 0개로 시간 끝 → 추리
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(2, null, -1, 75, false));
        g.passAll();
        assertThat(g.total(0)).isEqualTo(1);
        assertThat(g.state.revealed()).hasSize(2);
    }

    @Test
    void hints_of_every_attempt_accumulate_in_the_public_view_and_void_ones_are_left_out() {
        Game g = game();
        assertThat(view(g).hints()).isEmpty(); // 낱말 고르기
        g.selectAll(0);
        g.live = true;
        g.hint("여름").hint("배").passAll().timer(); // 1차: 둘, 2차 힌트 단계로
        g.hint("소금").hint("짠맛");
        assertThat(view(g).hints()).containsExactly(
                new WordTempViews.HintView("여름", 1, HintStatus.SCORED, 58),
                new WordTempViews.HintView("배", 1, HintStatus.SCORED, 70),
                new WordTempViews.HintView("소금", 2, HintStatus.SCORED, 62),
                new WordTempViews.HintView("짠맛", 2, HintStatus.PENDING, -1));
        assertThat(view(g).hintCount()).isEqualTo(2); // 개수는 이번 차수만
        g.score("짠맛", new WordTempEngine.LiveScore.NotANoun());
        assertThat(view(g).hints()).extracting(WordTempViews.HintView::text).containsExactly("여름", "배", "소금");
        g.hint("모래"); // 75 = 2차 기준 → 추리
        assertThat(view(g).hints()).hasSize(4);
        assertThat(view(g).revealed()).containsExactly(new Revealed(1, "배", 70, 70, true),
                new Revealed(2, "모래", 75, 75, true));
        g.guess(1, "바다").passAll().timer();
        assertThat(g.state.phase()).isEqualTo(Phase.TURN_END);
        assertThat(view(g).hints()).hasSize(4); // 차례 끝 동안에도 그대로
        g.timer();
        assertThat(view(g).hints()).isEmpty(); // 새 출제 차례
    }

    private static WordTempViews.PublicView view(Game g) {
        return WordTempViews.publicView(g.state, g.engine.config(), g.now);
    }

    // ---- §5.2 힌트 거절 ----

    @Test
    void rejects_bad_hints_without_spending_the_count() {
        Game g = game().selectAll(0);
        assertThat(g.rejected(0, new WordTempAction.EndHints(1, 1))).isEqualTo(RejectionReason.NO_HINTS_YET);
        assertThat(g.hintRejected("sea")).isEqualTo(RejectionReason.HINT_FORMAT);
        assertThat(g.hintRejected("")).isEqualTo(RejectionReason.HINT_FORMAT);
        assertThat(g.hintRejected("ㅂㄷ")).isEqualTo(RejectionReason.HINT_FORMAT);
        assertThat(g.hintRejected("가나다라마바사아자차카")).isEqualTo(RejectionReason.HINT_FORMAT);
        assertThat(g.hintRejected("바다")).isEqualTo(RejectionReason.HINT_IS_ANSWER);
        assertThat(g.hintRejected("바닷가")).isEqualTo(RejectionReason.UNKNOWN_WORD); // 포함 아님, 표 밖·실시간 꺼짐
        assertThat(g.hintRejected("바다낚시")).isEqualTo(RejectionReason.HINT_IS_ANSWER);
        assertThat(g.hintRejected("바 다 !")).isEqualTo(RejectionReason.HINT_IS_ANSWER);
        g.hint("여름");
        assertThat(g.hintRejected("여름")).isEqualTo(RejectionReason.HINT_DUPLICATE);
        assertThat(g.hintRejected(" 여름. ")).isEqualTo(RejectionReason.HINT_DUPLICATE);
        assertThat(g.state.hintCount()).isEqualTo(1);
        // 출제자가 아님·늦게 도착한 프레임
        assertThat(g.rejected(1, new WordTempAction.SubmitHint(1, 1, "소금"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(0, new WordTempAction.SubmitHint(1, 2, "소금"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(0, new WordTempAction.SubmitHint(2, 1, "소금"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(0, new WordTempAction.EndHints(1, 3))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(0, new WordTempAction.SubmitGuess(1, 1, "바다"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        // 마감이 지나면 새 힌트를 받지 않는다(전이는 타이머가 한다)
        g.after(90_000);
        assertThat(g.hintRejected("소금")).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
    }

    @Test
    void containment_rules_use_the_aliases_and_allow_one_syllable_inside_the_answer() {
        Game g = game().selectAll(1); // 좌석 0 의 정답 = 사과, 좌석 1 = 눈사람
        g.hint("과일").guess(1, "사과").passAll().until(Phase.TURN_END).timer(); // 과일 88 — 넘겼다, 바로 맞혀 끝
        assertThat(g.state.answer().word()).isEqualTo("눈사람");
        assertThat(g.hintRejected("사람")).isEqualTo(RejectionReason.HINT_INSIDE_ANSWER);
        assertThat(g.hintRejected("눈사람들")).isEqualTo(RejectionReason.HINT_IS_ANSWER);
        g.hint("눈"); // 1자 힌트는 정답 안에 들어 있어도 된다
        assertThat(g.event(WordTempEvent.HintScored.class).temp()).isEqualTo(90);
    }

    @Test
    void a_hint_used_in_an_earlier_attempt_of_the_same_turn_is_a_duplicate() {
        Game g = game().selectAll(0).hint("갈매기").passAll().timer();
        assertThat(g.state.attempt()).isEqualTo(2);
        assertThat(g.hintRejected("갈매기")).isEqualTo(RejectionReason.HINT_DUPLICATE);
        assertThat(g.state.hintCount()).isZero(); // 개수는 차수마다 다시 센다
    }

    // ---- §3.3·§5.3 추리 ----

    @Test
    void rejects_bad_guesses() {
        Game g = game().selectAll(0).hint("갈매기");
        assertThat(g.rejected(0, new WordTempAction.SubmitGuess(1, 1, "바다"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(1, new WordTempAction.SubmitGuess(1, 2, "바다"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        assertThat(g.rejected(1, new WordTempAction.SubmitGuess(1, 1, "sea"))).isEqualTo(RejectionReason.GUESS_FORMAT);
        assertThat(g.rejected(1, new WordTempAction.SubmitGuess(1, 1, "ㅂ"))).isEqualTo(RejectionReason.GUESS_FORMAT);
        assertThat(g.rejected(1, new WordTempAction.SubmitHint(1, 1, "소금"))).isEqualTo(RejectionReason.NOT_YOUR_PHASE);
        g.guess(1, "항구");
        assertThat(g.rejected(1, new WordTempAction.SubmitGuess(1, 1, "바다"))).isEqualTo(RejectionReason.ALREADY_GUESSED);
        assertThat(g.engine.pendingSeats(g.state)).containsExactly(2, 3);
    }

    @Test
    void whoever_did_not_guess_in_thirty_seconds_passes() {
        Game g = game().selectAll(0).hint("갈매기").guess(2, "바다");
        assertThat(g.engine.timer(g.state, g.now)).hasValue(30_000);
        g.timer();
        WordTempEvent.GuessesRevealed r = g.event(WordTempEvent.GuessesRevealed.class);
        assertThat(r.guesses().stream().map(WordTempEvent.RevealedGuess::pass)).containsExactly(true, false, true);
        assertThat(g.state.totals()).containsExactly(0, 0, 1, 0);
    }

    @Test
    void a_blocked_word_is_masked_for_everyone_else_but_kept_in_the_state() {
        Game g = new Game(WordTempConfig.of(4, Set.of(), 1, 90, WordTempBotLevel.NORMAL)
                .withBlockedWords(Set.of("바보")), 0);
        g.selectAll(0).hint("갈매기").guess(1, "왕바보").guess(2, "항구").guess(3, "");
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).guesses().get(0))
                .isEqualTo(new WordTempEvent.RevealedGuess(1, "···", false, false, true));
        assertThat(g.state.attempts().get(0).guesses().get(0).text()).isEqualTo("왕바보");
    }

    // ---- §3.4·§4 점수 표 ----

    @Test
    void two_correct_in_the_first_attempt_give_the_setter_nothing() {
        Game g = game().selectAll(0).hint("파도").guess(1, "바다").guess(2, "바다입니다").guess(3, "강");
        assertThat(g.state.totals()).containsExactly(0, 1, 1, 0);
        g.timer();
        assertThat(g.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(TurnEndReason.SOLVED);
    }

    @Test
    void five_attempts_all_wrong_over_rising_floors_give_the_setter_fifteen_and_end_the_turn() {
        Game g = game().selectAll(0);
        String[] passing = {"배", "모래", "항구", "해변", "파도"}; // 70·75·80·88·93
        int[] floors = {70, 75, 80, 85, 88};
        int total = 0;
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(g.state.attempt()).isEqualTo(attempt);
            g.hint(passing[attempt - 1]);
            WordTempEvent.HintRevealed shown = g.event(WordTempEvent.HintRevealed.class);
            assertThat(shown.floor()).isEqualTo(floors[attempt - 1]);
            assertThat(shown.floorMet()).isTrue();
            g.passAll();
            assertThat(g.event(WordTempEvent.GuessesRevealed.class).setterGain()).isEqualTo(attempt);
            total += attempt;
            assertThat(g.total(0)).isEqualTo(total);
            g.timer();
        }
        assertThat(g.total(0)).isEqualTo(15);
        assertThat(g.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(TurnEndReason.ALL_ATTEMPTS);
        assertThat(g.state.phase()).isEqualTo(Phase.TURN_END);
        assertThat(g.state.history().get(0).setterGain()).isEqualTo(15);
        assertThat(g.state.history().get(0).revealed()).hasSize(5).allMatch(Revealed::floorMet);
    }

    @Test
    void a_hint_that_met_an_earlier_floor_does_not_meet_the_next_one() {
        // 배(70)는 1차 기준이지만 2차 기준(75)에는 모자란다 — 차수마다 그 차수에 낸 힌트로 본다
        Game g = game().selectAll(0).hint("배").passAll().timer();
        assertThat(g.state.attempt()).isEqualTo(2);
        g.hint("섬"); // 72 < 75
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        g.endHints();
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(2, "섬", 72, 75, false));
    }

    @Test
    void an_attempt_below_the_floor_scores_nothing_for_the_setter_but_play_goes_on() {
        Game g = game().selectAll(0);
        g.hint("하늘").endHints(); // 31 < 70
        assertThat(g.event(WordTempEvent.HintRevealed.class).floorMet()).isFalse();
        g.passAll();
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).setterGain()).isZero();
        g.timer();
        assertThat(g.state.attempt()).isEqualTo(2);
        g.hint("모래").passAll().timer(); // 75 ≥ 75 → +2
        assertThat(g.total(0)).isEqualTo(2);
        g.hint("여름").endHints();        // 58 < 80
        assertThat(g.event(WordTempEvent.HintRevealed.class))
                .isEqualTo(new WordTempEvent.HintRevealed(3, "여름", 58, 80, false));
        g.guess(1, "바다").passAll();
        assertThat(g.state.totals()).containsExactly(2, 1, 0, 0);
    }

    @Test
    void only_the_attempts_that_met_their_floor_pay_and_the_fifth_ends_the_turn() {
        Game g = game().selectAll(0);
        g.timer().passAll().timer();                   // 1차: 힌트 0개 → 0
        g.hint("모래").passAll().timer();              // 2차: 75 → +2
        g.hint("갈매기").endHints().passAll().timer(); // 3차: 71 < 80 → 0
        g.hint("해변").passAll().timer();              // 4차: 88 → +4
        g.hint("항구").endHints().passAll();           // 5차: 80 < 90 → 0
        assertThat(g.total(0)).isEqualTo(6);
        assertThat(g.state.attempts()).extracting(a -> a.setterGain()).containsExactly(0, 2, 0, 4, 0);
        g.timer();
        assertThat(g.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(TurnEndReason.ALL_ATTEMPTS);
        assertThat(g.state.history().get(0).revealed()).extracting(Revealed::floorMet)
                .containsExactly(false, true, false, true, false);
        assertThat(g.state.history().get(0).reason()).isNotIn(TurnEndReason.NO_HINTS, TurnEndReason.THREE_ATTEMPTS);
    }

    @Test
    void floors_and_the_number_of_attempts_are_tunable() {
        WordTempTuning three = WordTempTuning.DEFAULT.withFloors(30, 30, 30);
        assertThat(three.maxAttempts()).isEqualTo(3);
        assertThat(WordTempTuning.DEFAULT.maxAttempts()).isEqualTo(5);
        assertThat(WordTempTuning.DEFAULT.maxHints()).isEqualTo(10);
        assertThat(WordTempTuning.DEFAULT.floors()).containsExactly(70, 75, 80, 85, 88);
        Game g = new Game(WordTempConfig.of(4, Set.of(), 1, 90, WordTempBotLevel.NORMAL).withTuning(three), 0);
        g.selectAll(0).hint("하늘"); // 31 ≥ 30 → 넘겼다
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        g.passAll();
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).setterGain()).isEqualTo(1);
        g.timer().hint("여행").passAll().timer().hint("여름").passAll().timer();
        assertThat(g.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(TurnEndReason.ALL_ATTEMPTS);
        assertThat(g.total(0)).isEqualTo(6);
    }

    @Test
    void points_are_multiplied_by_the_round_number() {
        Game g = game(3, Set.of(), 2);
        g.selectAll(0);
        for (int turn = 0; turn < 3; turn++) {
            g.until(Phase.HINT).idleTurn().timer(); // 힌트 0개·전원 패스로 5차까지 → 아무도 점수 없음
        }
        assertThat(g.state.totals()).containsOnly(0);
        assertThat(g.state.phase()).isEqualTo(Phase.WORD_SELECT);
        assertThat(g.state.round()).isEqualTo(2);
        assertThat(g.event(WordTempEvent.WordSelectStarted.class).round()).isEqualTo(2);
        WordTempEvent.WordCandidates mine = g.event(WordTempEvent.WordCandidates.class);
        assertThat(mine.candidates().stream().map(WordTempEvent.CandidateInfo::available))
                .containsExactly(false, true, true);
        assertThat(g.rejected(0, new WordTempAction.SelectWord(2, 0))).isEqualTo(RejectionReason.BAD_CHOICE);
        g.selectAll(1); // 좌석 0 = 사과
        assertThat(g.state.turnNo()).isEqualTo(4);
        g.hint("빨강").passAll(); // 70 — 1차 전원 오답 → +1 × 2
        assertThat(g.total(0)).isEqualTo(2);
        g.timer().hint("과일").guess(1, "사과").passAll(); // 2차 정답 → +1 × 2
        assertThat(g.state.totals()).containsExactly(2, 2, 0);
    }

    @Test
    void a_perfect_setter_turn_in_round_two_is_worth_thirty() {
        Game g = game(3, Set.of(), 2);
        g.selectAll(1);
        for (int turn = 0; turn < 3; turn++) {
            g.until(Phase.HINT).idleTurn().timer();
        }
        g.selectAll(0); // 2라운드 — 좌석 0 = 바다
        for (String passing : List.of("배", "모래", "항구", "해변", "파도")) {
            g.hint(passing).passAll().timer();
        }
        assertThat(g.total(0)).isEqualTo(15 * 2);
        assertThat(g.state.history().get(3).setterGain()).isEqualTo(30);
    }

    // ---- §8 끝 ----

    @Test
    void the_match_ends_after_the_last_turn_of_the_last_round_with_shared_ranks() {
        Game g = game(3, Set.of(), 1).selectAll(0);
        g.hint("소금").endHints().guess(1, "바다").passAll().until(Phase.TURN_END).timer(); // 좌석 1 +1
        g.hint("생일").guess(0, "케이크").passAll().until(Phase.TURN_END).timer();          // 좌석 0 +1
        g.hint("건반").passAll(); // 좌석 2 +1 (1차 전원 오답, 92 ≥ 70)
        g.until(Phase.HINT).idleTurn(); // 2~5차 힌트 0개 → 더는 점수 없음
        assertThat(g.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(TurnEndReason.ALL_ATTEMPTS);
        assertThat(g.state.phase()).isEqualTo(Phase.TURN_END);
        g.timer();
        assertThat(g.state.ended()).isTrue();
        var result = g.event(WordTempEvent.MatchEnded.class).result();
        assertThat(result.endReason()).isEqualTo(EndReason.FINISHED);
        assertThat(result.draw()).isTrue();
        assertThat(result.standings().stream().map(Standing::rank)).containsExactly(1, 1, 1);
        assertThat(result.standings().get(2).setterPoints()).isEqualTo(1);
        assertThat(result.standings().get(0).guessPoints()).isEqualTo(1);
        assertThat(g.engine.timer(g.state, g.now)).isEmpty();
        assertThat(g.rejected(0, new WordTempAction.SelectWord(1, 0))).isEqualTo(RejectionReason.MATCH_OVER);
        assertThat(g.engine.pendingSeats(g.state)).isEmpty();
    }

    @Test
    void the_setting_order_is_the_same_every_round_starting_from_a_random_seat() {
        Game g = new Game(WordTempConfig.of(4, Set.of(), 2, 90, WordTempBotLevel.NORMAL), 2);
        assertThat(g.state.order()).containsExactly(2, 3, 0, 1);
        g.selectAll(0);
        assertThat(g.state.setterSeat()).isEqualTo(2);
    }
}
