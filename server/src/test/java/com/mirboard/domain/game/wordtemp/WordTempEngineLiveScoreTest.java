package com.mirboard.domain.game.wordtemp;

import static com.mirboard.domain.game.wordtemp.WordTempFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.WordTempFixtures.Game;
import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintSource;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.VoidReason;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-wordtemp.md` §6.2·§6.3, 설계서 §4 — 표 밖 힌트의 "채점 중 → 반영" 경로. 엔진은 I/O 를 하지 않는다: 결과는 테스트(= 어댑터 자리)가
 * {@code applyScore} 로 넣고, 답이 없으면 엔진 타이머가 8초 뒤 무효로 만든다.
 */
class WordTempEngineLiveScoreTest {

    private static Game pendingHint() {
        Game g = game().selectAll(0);
        g.live = true;
        return g.hint("여름").after(1_000).hint("짠맛");
    }

    @Test
    void an_unknown_hint_is_rejected_when_live_scoring_is_off_and_keeps_the_count() {
        Game g = game().selectAll(0);
        assertThat(g.hintRejected("짠맛")).isEqualTo(RejectionReason.UNKNOWN_WORD);
        assertThat(g.state.hintCount()).isZero();
        assertThat(g.state.liveRequests()).isZero();
    }

    @Test
    void an_unknown_hint_goes_pending_takes_a_slot_and_tells_only_the_setter() {
        Game g = pendingHint();
        assertThat(g.events).containsExactly(new WordTempEvent.HintPending(0, "짠맛"),
                new WordTempEvent.HintCount(2, 5));
        assertThat(g.state.pendingHint().status()).isEqualTo(HintStatus.PENDING);
        assertThat(g.state.pendingHint().requestedAt()).isEqualTo(g.now);
        assertThat(g.state.pendingHint().source()).isEqualTo(HintSource.LIVE);
        assertThat(g.state.liveRequests()).isEqualTo(1);
        // 한 번에 하나 — 새 힌트·[힌트 끝]을 받지 않는다
        assertThat(g.hintRejected("소금")).isEqualTo(RejectionReason.SCORING_IN_PROGRESS);
        assertThat(g.rejected(0, new WordTempAction.EndHints(1, 1))).isEqualTo(RejectionReason.SCORING_IN_PROGRESS);
        // 타이머는 기다림 상한(8초)을 본다 — 단계 마감(90초)이 아니다
        assertThat(g.engine.timer(g.state, g.now)).hasValue(8_000);
    }

    @Test
    void a_scored_result_fixes_the_temperature_in_the_state() {
        Game g = pendingHint().after(2_500).score("짠맛", new LiveScore.Scored(64));
        assertThat(g.events).containsExactly(new WordTempEvent.HintScored(0, "짠맛", 64, 2));
        assertThat(g.state.pendingHint()).isNull();
        assertThat(g.state.hints().get(1).temp()).isEqualTo(64);
        assertThat(g.state.hints().get(1).status()).isEqualTo(HintStatus.SCORED);
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        // 같은 쌍을 이 차례에 다시 낼 수는 없다
        assertThat(g.hintRejected("짠맛")).isEqualTo(RejectionReason.HINT_DUPLICATE);
        g.endHints();
        assertThat(g.event(WordTempEvent.HintRevealed.class).text()).isEqualTo("짠맛");
    }

    @Test
    void scores_outside_zero_to_hundred_are_clamped() {
        assertThat(pendingHint().score("짠맛", new LiveScore.Scored(140)).state.phase()).isEqualTo(Phase.GUESS);
        Game low = pendingHint().score("짠맛", new LiveScore.Scored(-5));
        assertThat(low.state.hints().get(1).temp()).isZero();
    }

    @Test
    void a_live_score_of_ninety_six_or_more_ends_the_hint_phase() {
        Game g = pendingHint().score("짠맛", new LiveScore.Scored(96));
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.event(WordTempEvent.HintRevealed.class).text()).isEqualTo("짠맛");
    }

    @Test
    void not_a_noun_voids_the_hint_and_refunds_the_count() {
        Game g = pendingHint().score("짠맛", new LiveScore.NotANoun());
        assertThat(g.events).containsExactly(new WordTempEvent.HintVoid(0, "짠맛", VoidReason.NOT_A_NOUN, 1),
                new WordTempEvent.HintCount(1, 5));
        assertThat(g.state.hintCount()).isEqualTo(1);
        assertThat(g.state.hints().get(1).status()).isEqualTo(HintStatus.VOID);
        // 무효가 된 낱말은 다시 낼 수 있다(§5.2)
        g.hint("짠맛");
        assertThat(g.has(WordTempEvent.HintPending.class)).isTrue();
        assertThat(g.state.liveRequests()).isEqualTo(2);
    }

    @Test
    void a_failed_result_and_an_eight_second_silence_both_void_the_hint() {
        Game failed = pendingHint().score("짠맛", new LiveScore.Failed());
        assertThat(failed.event(WordTempEvent.HintVoid.class).reason()).isEqualTo(VoidReason.SCORING_FAILED);

        Game silent = pendingHint();
        // 결과가 오기 전의 발화는 아무것도 바꾸지 않는다(어댑터의 250ms 폴링)
        var early = silent.engine.onTimer(silent.state, silent.now + 250);
        assertThat(early.newState()).isSameAs(silent.state);
        assertThat(early.events()).isEmpty();
        silent.timer();
        assertThat(silent.events).containsExactly(new WordTempEvent.HintVoid(0, "짠맛", VoidReason.SCORING_FAILED, 1),
                new WordTempEvent.HintCount(1, 5));
        assertThat(silent.state.phase()).isEqualTo(Phase.HINT);
        assertThat(silent.engine.timer(silent.state, silent.now)).hasValue(90_000 - 1_000 - 8_000);
    }

    @Test
    void a_result_for_something_that_is_no_longer_pending_changes_nothing() {
        Game g = pendingHint();
        assertThat(g.engine.applyScore(g.state, "다른말", new LiveScore.Scored(50), g.now).newState())
                .isSameAs(g.state);
        g.score("짠맛", new LiveScore.Scored(64));
        var late = g.engine.applyScore(g.state, "짠맛", new LiveScore.Scored(99), g.now);
        assertThat(late.newState()).isSameAs(g.state);
        assertThat(late.events()).isEmpty();
    }

    @Test
    void the_hint_limit_running_out_while_scoring_waits_for_the_result_then_closes() {
        // 마감 1초 전에 낸 힌트가 채점 중인 채로 마감을 넘긴다
        Game late = game().selectAll(0);
        late.live = true;
        late.after(89_000).hint("짠맛").after(1_500);
        assertThat(late.now).isGreaterThan(late.engine.deadline(late.state));
        // 단계 마감은 미뤄지고(타이머는 기다림 상한), TICK 도 아무것도 하지 않는다
        assertThat(late.engine.timer(late.state, late.now)).hasValue(6_500);
        assertThat(late.engine.apply(late.state, 0, new WordTempAction.Tick(1, 1, Phase.HINT), late.now).newState())
                .isSameAs(late.state);
        late.score("짠맛", new LiveScore.Scored(64));
        assertThat(late.events.get(0)).isEqualTo(new WordTempEvent.HintScored(0, "짠맛", 64, 1));
        assertThat(late.event(WordTempEvent.HintRevealed.class).text()).isEqualTo("짠맛");
        assertThat(late.state.phase()).isEqualTo(Phase.GUESS);
    }

    @Test
    void a_void_after_the_limit_with_no_other_hint_ends_the_turn_with_no_hints() {
        Game g = game().selectAll(0);
        g.live = true;
        g.after(89_000).hint("짠맛").timer(); // 8초 침묵 → 무효, 이미 마감이 지남
        assertThat(g.has(WordTempEvent.HintVoid.class)).isTrue();
        assertThat(g.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(TurnEndReason.NO_HINTS);
        assertThat(g.event(WordTempEvent.TurnEnded.class).hints()).isEmpty();
    }

    @Test
    void the_fifth_slot_pending_closes_the_phase_only_once_it_is_scored() {
        Game g = game().selectAll(0);
        g.live = true;
        g.hint("여름").hint("여행").hint("하늘").hint("소금").hint("짠맛");
        assertThat(g.state.phase()).isEqualTo(Phase.HINT);
        assertThat(g.state.hintCount()).isEqualTo(5);
        g.score("짠맛", new LiveScore.Scored(40));
        assertThat(g.state.phase()).isEqualTo(Phase.GUESS);
        assertThat(g.state.revealed().get(0).text()).isEqualTo("소금");
    }

    @Test
    void the_per_match_cap_turns_unknown_hints_into_rejections() {
        Game g = new Game(WordTempConfig.of(4, Set.of(), 1, 90, WordTempBotLevel.NORMAL).withLiveCap(1), 0);
        g.live = true;
        g.selectAll(0).hint("짠맛").score("짠맛", new LiveScore.NotANoun());
        assertThat(g.hintRejected("짠맛")).isEqualTo(RejectionReason.UNKNOWN_WORD);
        assertThat(g.state.liveRequests()).isEqualTo(1);
    }

    @Test
    void a_pending_hint_is_dropped_when_the_turn_ends_and_never_shown() {
        Game g = pendingHint().desert(0);
        WordTempEvent.TurnEnded ended = g.event(WordTempEvent.TurnEnded.class);
        assertThat(ended.reason()).isEqualTo(TurnEndReason.SETTER_OUT);
        assertThat(ended.hints()).containsExactly(new WordTempEvent.HintInfo(1, "여름", 58, false));
        assertThat(g.state.pendingHint()).isNull();
        assertThat(g.engine.applyScore(g.state, "짠맛", new LiveScore.Scored(64), g.now).newState())
                .isSameAs(g.state);
    }
}
