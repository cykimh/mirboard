package com.mirboard.domain.game.wordtemp;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.event.WordTempMatchCompleted;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintSource;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.VoidReason;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.PrivateView;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.PublicView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * S2 포트 어댑터(D-316·D-317) — 표 힌트·실시간 채점의 전 갈래(설계서 §4.2·§4.4·§4.5), 타이머 합성, 멱등 {@code TICK}, 봇, 뷰, 탈주·기록
 * 발행. 인프라가 부르는 순서는 {@link WordTempAdapterHarness} 가 흉내 낸다. Docker 불필요 — 채점기는 가짜다(네트워크 호출 0).
 */
class WordTempGameEngineTest {

    // ---- 표 힌트 ----

    @Test
    void a_table_hint_is_scored_inside_apply_without_asking_the_scorer() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.hint("여름");
        assertThat(h.event(WordTempEvent.HintScored.class).temp()).isEqualTo(58);
        assertThat(h.event(WordTempEvent.HintCount.class).count()).isEqualTo(1);
        assertThat(h.state().hints().get(0).source()).isEqualTo(HintSource.TABLE);
        assertThat(h.scorer.requests).isEmpty();
        // 채점 중이 아니면 폴링하지 않는다 — 다음 타이머는 단계 마감(힌트 제한 90초)
        assertThat(h.adapter.timer(h.state())).hasValue(Duration.ofMillis(90_000));
    }

    // ---- 실시간 채점: 채점 중 → 반영 ----

    @Test
    void an_out_of_table_hint_goes_pending_asks_the_scorer_once_and_is_applied_by_the_timer() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.hint("여름").after(1_000).hint("짠맛");

        assertThat(h.event(WordTempEvent.HintPending.class).text()).isEqualTo("짠맛");
        assertThat(h.event(WordTempEvent.HintCount.class).count()).isEqualTo(2);
        assertThat(h.state().pendingHint().text()).isEqualTo("짠맛");
        assertThat(h.scorer.requests).containsExactly("바다/짠맛");
        // 채점 중에는 새 힌트·[힌트 끝]을 받지 않는다(한 번에 하나) — 채점기에 더 묻지도 않는다
        assertThat(h.hintRejected("갈매기")).isEqualTo("SCORING_IN_PROGRESS");
        WordTempState s = h.state();
        assertThat(h.rejected(0, new WordTempAction.EndHints(s.turnNo(), s.attempt()))).isEqualTo("SCORING_IN_PROGRESS");
        assertThat(h.scorer.requests).hasSize(1);

        // 결과가 오기 전 — 250ms 뒤에 다시 묻는다(저장·방송 없음). 결과가 오면 0.
        assertThat(h.adapter.timer(h.state())).hasValue(Duration.ofMillis(250));
        assertThat(h.adapter.onTimer(h.state())).as("아직 만기 아님").isEmpty();
        h.after(2_100);
        h.scorer.deliver("바다", "짠맛", new LiveScore.Scored(64));
        assertThat(h.adapter.timer(h.state())).hasValue(Duration.ZERO);

        h.fire();
        assertThat(h.rearms).isZero();
        WordTempEvent.HintScored scored = h.event(WordTempEvent.HintScored.class);
        assertThat(scored.privateSeat()).isZero();
        assertThat(scored.temp()).isEqualTo(64);
        assertThat(scored.count()).isEqualTo(2);
        assertThat(h.events).as("개수는 그대로라 공개 이벤트가 없다").allMatch(GameEvent::isPrivate);
        assertThat(h.state().pendingHint()).isNull();
        assertThat(h.state().hints().get(1).status()).isEqualTo(HintStatus.SCORED);
        assertThat(h.state().hints().get(1).source()).isEqualTo(HintSource.LIVE);
        assertThat(h.state().liveRequests()).isEqualTo(1);
        // 풀렸다 — 다시 단계 마감까지
        assertThat(h.adapter.timer(h.state()).orElseThrow().toMillis()).isEqualTo(90_000 - 3_100);
    }

    @Test
    void polling_rearms_every_250ms_until_the_result_arrives() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.hint("짠맛");
        // 결과는 세 번째 폴링 뒤에 온다
        for (int i = 0; i < 3; i++) {
            assertThat(h.adapter.timer(h.state())).hasValue(Duration.ofMillis(250));
            assertThat(h.adapter.onTimer(h.state())).isEmpty();
            h.after(250);
        }
        h.scorer.deliver("바다", "짠맛", new LiveScore.Scored(64));
        h.fire();
        assertThat(h.rearms).isZero();
        assertThat(h.event(WordTempEvent.HintScored.class).temp()).isEqualTo(64);
    }

    @Test
    void a_live_score_of_96_or_more_closes_the_hint_phase_and_reveals_it() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.scorer.answerWith("대양", new LiveScore.Scored(97));
        h.hint("여름").hint("대양").fire();
        assertThat(h.state().phase()).isEqualTo(Phase.GUESS);
        WordTempEvent.HintRevealed revealed = h.event(WordTempEvent.HintRevealed.class);
        assertThat(revealed.text()).isEqualTo("대양");
        assertThat(revealed.temp()).isEqualTo(97);
    }

    @Test
    void a_score_outside_0_to_100_is_clamped() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.scorer.answerWith("짠맛", new LiveScore.Scored(-7));
        h.hint("짠맛").fire();
        assertThat(h.event(WordTempEvent.HintScored.class).temp()).isZero();
    }

    // ---- 실시간 채점: 무효 + 개수 반환 ----

    @Test
    void a_non_noun_is_voided_and_the_slot_is_refunded() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.scorer.answerWith("베토벤", new LiveScore.NotANoun());
        h.hint("여름").hint("베토벤");
        assertThat(h.event(WordTempEvent.HintCount.class).count()).isEqualTo(2);
        h.fire();
        WordTempEvent.HintVoid voided = h.event(WordTempEvent.HintVoid.class);
        assertThat(voided.reason()).isEqualTo(VoidReason.NOT_A_NOUN);
        assertThat(voided.count()).isEqualTo(1);
        assertThat(h.event(WordTempEvent.HintCount.class).count()).as("공개 개수도 돌아간다").isEqualTo(1);
        assertThat(h.state().hintCount()).isEqualTo(1);
        assertThat(h.state().phase()).isEqualTo(Phase.HINT);
    }

    @Test
    void no_answer_within_eight_seconds_voids_the_hint_and_the_same_word_can_be_asked_again() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.hint("짠맛");
        h.fire();
        assertThat(h.rearms).as("8초 / 250ms").isEqualTo(32);
        WordTempEvent.HintVoid voided = h.event(WordTempEvent.HintVoid.class);
        assertThat(voided.reason()).isEqualTo(VoidReason.SCORING_FAILED);
        assertThat(voided.count()).isZero();
        assertThat(h.event(WordTempEvent.HintCount.class).count()).isZero();
        assertThat(h.state().pendingHint()).isNull();

        // 무효가 된 힌트는 다시 낼 수 있다 — 다시 묻는다. 뒤늦게 온 답이 이번에는 반영된다.
        h.hint("짠맛");
        assertThat(h.scorer.requests).containsExactly("바다/짠맛", "바다/짠맛");
        h.scorer.deliver("바다", "짠맛", new LiveScore.Scored(64));
        h.fire();
        assertThat(h.event(WordTempEvent.HintScored.class).temp()).isEqualTo(64);
        assertThat(h.state().liveRequests()).isEqualTo(2);
    }

    @Test
    void a_scorer_failure_voids_at_once_without_waiting_for_the_cap() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.scorer.answerWith("짠맛", new LiveScore.Failed());
        h.hint("짠맛").fire();
        assertThat(h.rearms).isZero();
        assertThat(h.event(WordTempEvent.HintVoid.class).reason()).isEqualTo(VoidReason.SCORING_FAILED);
    }

    @Test
    void the_hint_limit_running_out_while_scoring_waits_for_the_result_then_closes() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.hint("여름").after(89_000).hint("짠맛");
        h.after(2_000); // 힌트 제한(90초)은 지났다
        // 채점 중에는 단계 마감 대신 폴링 — TICK(턴 타임아웃)도 무변경
        assertThat(h.adapter.timer(h.state())).hasValue(Duration.ofMillis(250));
        h.timeout();
        assertThat(h.events).isEmpty();
        assertThat(h.state().phase()).isEqualTo(Phase.HINT);
        // 마감 뒤에는 새 힌트를 받지 않는다
        assertThat(h.hintRejected("갈매기")).isEqualTo("NOT_YOUR_PHASE");

        h.scorer.deliver("바다", "짠맛", new LiveScore.Scored(64));
        h.fire();
        assertThat(h.state().phase()).isEqualTo(Phase.GUESS);
        assertThat(h.event(WordTempEvent.HintRevealed.class).text()).isEqualTo("짠맛");
    }

    @Test
    void the_only_hint_voided_after_the_limit_ends_the_turn_with_no_hints() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.after(89_500).hint("짠맛");
        h.fire(); // 8초 뒤 무효 → 힌트 0개 → 출제 차례 끝
        assertThat(h.event(WordTempEvent.HintVoid.class).reason()).isEqualTo(VoidReason.SCORING_FAILED);
        assertThat(h.state().phase()).isEqualTo(Phase.TURN_END);
        assertThat(h.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(WordTempState.TurnEndReason.NO_HINTS);
    }

    // ---- 끄기 스위치·상한 — 깨끗한 거절, 게임은 계속 ----

    @Test
    void with_live_scoring_unavailable_an_out_of_table_hint_is_rejected_cleanly_and_the_table_still_works() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.scorer.available = false; // 끄기 스위치·API 키 없음·하루 상한 — 채점기가 한 곳에서 판단한다
        assertThat(h.hintRejected("짠맛")).isEqualTo("UNKNOWN_WORD");
        assertThat(h.state().hintCount()).as("개수 유지").isZero();
        assertThat(h.state().liveRequests()).isZero();
        assertThat(h.scorer.requests).isEmpty();
        h.hint("갈매기").endHints();
        assertThat(h.state().phase()).isEqualTo(Phase.GUESS);
    }

    @Test
    void the_per_match_cap_rejects_further_out_of_table_hints() {
        WordTempAdapterHarness h = new WordTempAdapterHarness(4, Set.of(), 1, WordTempFixtures.table(), null);
        WordTempGameEngine capped = new WordTempGameEngine(h.adapter.context(), h.config.withLiveCap(1),
                WordTempFixtures.table(), "t", h.scorer, h.store, java.time.Clock.systemUTC(), h.published::add);
        WordTempState s = capped.rules().start(WordTempFixtures.candidates(4, 1), WordTempFixtures.seedStartingAt(4, 0),
                System.currentTimeMillis()).newState();
        for (int seat = 0; seat < 4; seat++) {
            s = (WordTempState) capped.apply(s, seat, new WordTempAction.SelectWord(1, 0)).newState();
        }
        s = (WordTempState) capped.apply(s, 0, new WordTempAction.SubmitHint(1, 1, "짠맛")).newState();
        s = capped.rules().applyScore(s, "짠맛", new LiveScore.Scored(64), System.currentTimeMillis()).newState();
        WordTempState after = s;
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> capped.apply(after, 0, new WordTempAction.SubmitHint(1, 1, "소금물")))
                .hasMessageContaining("UNKNOWN_WORD");
    }

    @Test
    void a_restart_that_lost_the_result_box_is_recovered_by_polling() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.hint("짠맛");
        h.scorer.forget();          // 재시작 — 상태에는 채점 중, 결과함은 비었다
        h.scorer.available = false; // 그리고 이제 맡길 수 없다 → 운영 채점기는 poll 에서 실패를 돌려준다
        h.scorer.deliver("바다", "짠맛", new LiveScore.Failed());
        h.fire();
        assertThat(h.rearms).isZero();
        assertThat(h.event(WordTempEvent.HintVoid.class).reason()).isEqualTo(VoidReason.SCORING_FAILED);
    }

    // ---- TICK (턴 타임아웃) ----

    @Test
    void the_timeout_tick_is_idempotent_and_matches_the_engine_timer() {
        WordTempAdapterHarness a = WordTempAdapterHarness.hinting();
        WordTempAdapterHarness b = WordTempAdapterHarness.hinting();
        a.hint("갈매기");
        b.hint("갈매기");
        WordTempAction.Tick tick = (WordTempAction.Tick) a.adapter.timeoutAction(a.state(), 0);
        assertThat(tick).isEqualTo(new WordTempAction.Tick(1, 1, Phase.HINT));

        // 마감 전 — 무변경
        a.timeout();
        assertThat(a.events).isEmpty();
        assertThat(a.state().phase()).isEqualTo(Phase.HINT);

        // 마감 뒤 — 엔진 타이머와 같은 전이
        a.after(90_000).timeout();
        b.fire();
        assertThat(a.state().phase()).isEqualTo(Phase.GUESS);
        assertThat(a.events).isEqualTo(b.events);

        // 다음 단계에서 발화한 낡은 TICK 은 무변경(힌트를 낼 때마다 밀려 다시 걸린 데드라인)
        WordTempState before = a.state();
        a.act(1, tick);
        assertThat(a.events).isEmpty();
        assertThat(a.state()).isEqualTo(before);
    }

    // ---- 봇 ----

    @Test
    void bots_are_never_pending_and_the_engine_timer_plays_them_to_the_end() {
        WordTempAdapterHarness h = new WordTempAdapterHarness(4, Set.of(1, 2, 3), 1, WordTempFixtures.table(), null);
        h.start(1);
        assertThat(h.adapter.pendingSeats(h.state())).containsExactly(0);
        assertThat(h.adapter.botAction(h.state(), 1, new Random(1))).as("봇 좌석은 인프라 봇 루프의 대상이 아니다").isNull();
        assertThat(h.adapter.botAction(h.state(), 0, new Random(1))).isInstanceOf(WordTempAction.Tick.class);
        assertThat(h.adapter.legalActions(h.state(), 0)).isEmpty();
        h.act(0, new WordTempAction.SelectWord(1, 0));

        // 사람(좌석 0)은 손을 놓는다 — 봇 출제·봇 추리·단계 마감이 전부 엔진 타이머로 흘러 판이 끝난다
        List<GameEvent> all = new ArrayList<>();
        for (int i = 0; i < 2_000 && !h.state().ended(); i++) {
            h.fire();
            all.addAll(h.events);
        }
        assertThat(h.state().ended()).isTrue();
        assertThat(h.state().result().endReason()).isEqualTo(EndReason.FINISHED);
        assertThat(h.state().history()).hasSize(4);
        assertThat(h.scorer.requests).as("봇은 표 안의 낱말만 쓴다 — 실시간 호출 0").isEmpty();
        assertThat(all).anyMatch(WordTempEvent.MatchEnded.class::isInstance);
        assertThat(h.adapter.timer(h.state())).as("끝난 판은 타이머 없음").isEmpty();
        assertThat(h.adapter.onTimer(h.state())).isEmpty();
        assertThat(h.adapter.isRoundOver(h.state())).isTrue();
        assertThat(h.adapter.isMatchOver()).isTrue();
        assertThat(h.published).hasSize(1).first().isInstanceOf(WordTempMatchCompleted.class);
    }

    // ---- 뷰 ----

    @Test
    void the_public_view_has_the_phase_clock_and_the_private_view_belongs_to_live_humans_only() {
        WordTempAdapterHarness h = new WordTempAdapterHarness(4, Set.of(3), 1, WordTempFixtures.table(), null);
        h.start(0);
        for (int seat = 0; seat < 3; seat++) {
            h.act(seat, new WordTempAction.SelectWord(1, 0));
        }
        h.hint("여름").after(10_000);
        PublicView pub = (PublicView) h.adapter.publicView(h.state());
        assertThat(pub.phase()).isEqualTo(Phase.HINT);
        assertThat(pub.phaseRemainingMs()).isEqualTo(80_000);
        assertThat(pub.hintCount()).isEqualTo(1);
        assertThat(h.adapter.phaseName(h.state())).isEqualTo("HINT");

        PrivateView setter = (PrivateView) h.adapter.privateView(h.state(), 0).orElseThrow();
        assertThat(setter.answer()).isEqualTo("바다");
        assertThat(setter.hints()).hasSize(1);
        PrivateView guesser = (PrivateView) h.adapter.privateView(h.state(), 1).orElseThrow();
        assertThat(guesser.answer()).isNull();
        assertThat(guesser.hints()).isEmpty();
        assertThat(h.adapter.privateView(h.state(), 3)).as("봇").isEmpty();
        assertThat(h.adapter.privateView(h.state(), -1)).as("관전자").isEmpty();
        assertThat(h.adapter.privateView(h.state(), 9)).isEmpty();
    }

    // ---- 탈주·기록 ----

    @Test
    void a_setter_leaving_mid_scoring_ends_the_turn_and_the_late_result_changes_nothing() {
        WordTempAdapterHarness h = WordTempAdapterHarness.hinting();
        h.hint("여름").hint("짠맛");
        assertThat(h.desert(0)).isEqualTo(GameEngine.DesertOutcome.MATCH_CONTINUES);
        assertThat(h.has(WordTempEvent.SeatOut.class)).isTrue();
        assertThat(h.event(WordTempEvent.TurnEnded.class).reason()).isEqualTo(WordTempState.TurnEndReason.SETTER_OUT);
        assertThat(h.state().phase()).isEqualTo(Phase.TURN_END);
        assertThat(h.state().pendingHint()).as("채점 중이던 힌트는 버린다").isNull();
        // 뒤늦은 결과 — 타이머는 폴링하지 않고 차례 끝 6초를 센다
        h.scorer.deliver("바다", "짠맛", new LiveScore.Scored(64));
        assertThat(h.adapter.timer(h.state())).hasValue(Duration.ofMillis(6_000));
        assertThat(h.desert(0)).as("이미 빠진 좌석").isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
        assertThat(h.published).isEmpty();
    }

    @Test
    void desertion_down_to_one_seat_ends_the_match_and_publishes_one_record_event() {
        WordTempAdapterHarness h = new WordTempAdapterHarness(3, Set.of(), 1, WordTempFixtures.table(), null);
        h.start(0);
        assertThat(h.desert(1)).isEqualTo(GameEngine.DesertOutcome.MATCH_CONTINUES);
        assertThat(h.desert(2)).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
        assertThat(h.state().result().endReason()).isEqualTo(EndReason.LAST_SEAT);
        assertThat(h.events.getLast()).isInstanceOf(WordTempEvent.MatchEnded.class);
        assertThat(h.published).hasSize(1);
        WordTempMatchCompleted completed = (WordTempMatchCompleted) h.published.get(0);
        assertThat(completed.roomId()).isEqualTo(WordTempAdapterHarness.ROOM);
        assertThat(completed.playerIds()).isEqualTo(h.players);
        assertThat(completed.tableVersion()).isEqualTo("test-table");
        assertThat(completed.result().standings()).extracting(WordTempState.Standing::out)
                .containsExactly(false, true, true);
        assertThat(h.desert(0)).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
        assertThat(h.published).hasSize(1);
    }

    /** 매치 기록기 계약 3 — 기록(발행)이 실패해도 마지막 전이는 저장되고 판은 끝난다. */
    @Test
    void a_failing_recorder_does_not_stop_the_last_transition() {
        WordTempAdapterHarness h = new WordTempAdapterHarness(3, Set.of(), 1, WordTempFixtures.table(), event -> {
            throw new IllegalStateException("simulated DB outage in WordTempMatchRecorder");
        });
        h.start(0);
        h.desert(1);
        assertThat(h.desert(2)).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
        assertThat(h.state().ended()).isTrue();
        assertThat(h.events.getLast()).isInstanceOf(WordTempEvent.MatchEnded.class);
    }

    @Test
    void the_scorer_gets_the_answer_gloss_and_nothing_about_the_room() {
        // 채점기 인터페이스가 받는 것은 (정답 항목, 힌트)뿐이다 — 방·좌석·사용자를 넘길 자리가 없다(설계서 R14).
        assertThat(java.util.Arrays.stream(com.mirboard.domain.game.wordtemp.scoring.LiveHintScorer.class.getMethods())
                .filter(m -> m.getName().equals("request")).findFirst().orElseThrow().getParameterTypes())
                .containsExactly(com.mirboard.domain.game.wordtemp.words.AnswerEntry.class, String.class);
    }
}
