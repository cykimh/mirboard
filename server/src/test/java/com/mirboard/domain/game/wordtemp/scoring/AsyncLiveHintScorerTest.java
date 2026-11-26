package com.mirboard.domain.game.wordtemp.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.WordTempAdapterHarness.MutableClock;
import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.scoring.HintJudge.JudgeException;
import com.mirboard.domain.game.wordtemp.scoring.HintJudge.Verdict;
import com.mirboard.domain.game.wordtemp.scoring.LiveScoreStore.Key;
import com.mirboard.domain.game.wordtemp.scoring.LiveScoreStore.Stored;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * D-317 — 실시간 채점기의 안전장치와 결과함(가짜 모델·메모리 캐시 — 네트워크·DB 0): 캐시 적중이면 호출 없음, 처음 쓴 값이 정본, 끄기 스위치·
 * 키 없음·하루 상한, 400·401 이면 일시 중지, 같은 쌍은 작업 하나, 실패는 캐시에 안 씀, 재시작 뒤 폴링이 다시 건다.
 */
class AsyncLiveHintScorerTest {

    private static final AnswerEntry SEA = new AnswerEntry("바다", "자연", "짠물이 넓게 고인 곳", List.of());

    private final MemoryStore store = new MemoryStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T03:00:00Z").toEpochMilli());
    private final ScriptedJudge judge = new ScriptedJudge();
    private AsyncLiveHintScorer scorer;

    private AsyncLiveHintScorer scorer(boolean enabled, int dailyCap, HintJudge judge) {
        scorer = new AsyncLiveHintScorer(enabled, "model-x", "score-v2", dailyCap, 2, judge, store, clock);
        return scorer;
    }

    @AfterEach
    void stop() {
        if (scorer != null) {
            scorer.shutdown();
        }
    }

    private LiveScore await(AsyncLiveHintScorer s, String hint) {
        return Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5))
                .until(() -> s.poll(SEA, hint), Optional::isPresent).orElseThrow();
    }

    @Test
    void a_new_pair_is_judged_once_cached_and_the_result_stays_in_the_box() {
        AsyncLiveHintScorer s = scorer(true, 500, judge.say("짠맛", new Verdict(true, 64, "바닷물의 맛")));
        assertThat(s.available()).isTrue();
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(64));
        // 엔진 타이머는 timer·onTimer 에서 두 번 묻는다 — 같은 답이 계속 나온다
        assertThat(s.poll(SEA, "짠맛")).contains(new LiveScore.Scored(64));
        assertThat(judge.calls).containsExactly("바다|짠물이 넓게 고인 곳|짠맛");
        assertThat(store.rows).containsEntry(new Key("바다", "짠맛", "model-x", "score-v2"), new Stored(true, 64));
        assertThat(s.callsToday()).isEqualTo(1);
        // 결과가 있는 쌍을 다시 맡겨도 모델을 다시 부르지 않는다
        s.request(SEA, "짠맛");
        assertThat(s.poll(SEA, "짠맛")).contains(new LiveScore.Scored(64));
        assertThat(judge.calls).hasSize(1);
    }

    @Test
    void a_cached_pair_never_reaches_the_model_and_counts_a_hit() {
        Key key = new Key("바다", "짠맛", "model-x", "score-v2");
        store.rows.put(key, new Stored(true, 61));
        AsyncLiveHintScorer s = scorer(true, 500, judge);
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(61));
        assertThat(judge.calls).isEmpty();
        assertThat(store.hits.get(key).get()).isEqualTo(1);
        assertThat(s.callsToday()).as("캐시 적중은 하루 상한을 쓰지 않는다").isZero();
    }

    @Test
    void the_cache_key_includes_model_and_prompt_version() {
        store.rows.put(new Key("바다", "짠맛", "model-x", "score-v1"), new Stored(true, 10));
        store.rows.put(new Key("바다", "짠맛", "other-model", "score-v2"), new Stored(true, 20));
        AsyncLiveHintScorer s = scorer(true, 500, judge.say("짠맛", new Verdict(true, 64, "")));
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(64));
        assertThat(judge.calls).hasSize(1);
    }

    @Test
    void the_first_stored_value_wins_over_a_later_answer() {
        Key key = new Key("바다", "짠맛", "model-x", "score-v2");
        // 캐시를 읽은 뒤 다른 인스턴스가 먼저 쓴 경우
        AsyncLiveHintScorer s = scorer(true, 500, (answer, hint) -> {
            store.rows.put(key, new Stored(true, 70));
            return new Verdict(true, 64, "");
        });
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(70));
    }

    @Test
    void a_non_noun_is_cached_as_invalid_and_out_of_range_scores_are_clamped() {
        AsyncLiveHintScorer s = scorer(true, 500, judge.say("베토벤", new Verdict(false, 55, "사람 이름"))
                .say("파도", new Verdict(true, 140, "")));
        s.request(SEA, "베토벤");
        s.request(SEA, "파도");
        assertThat(await(s, "베토벤")).isEqualTo(new LiveScore.NotANoun());
        assertThat(await(s, "파도")).isEqualTo(new LiveScore.Scored(100));
        assertThat(store.rows.get(new Key("바다", "베토벤", "model-x", "score-v2"))).isEqualTo(new Stored(false, 0));
        assertThat(store.rows.get(new Key("바다", "파도", "model-x", "score-v2"))).isEqualTo(new Stored(true, 100));
    }

    @Test
    void the_kill_switch_or_a_missing_key_makes_it_unavailable_and_polling_releases_stuck_hints() {
        AsyncLiveHintScorer off = scorer(false, 500, judge);
        assertThat(off.available()).isFalse();
        assertThat(off.poll(SEA, "짠맛")).as("꺼졌는데 채점 중으로 남은 힌트는 실패로 풀린다").contains(new LiveScore.Failed());
        off.shutdown();

        AsyncLiveHintScorer noKey = scorer(true, 500, null);
        assertThat(noKey.available()).isFalse();
        assertThat(noKey.poll(SEA, "짠맛")).contains(new LiveScore.Failed());
        assertThat(judge.calls).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void the_daily_cap_counts_todays_cached_rows_plus_local_calls_and_resets_on_the_next_seoul_day() {
        store.createdToday = 2;
        AsyncLiveHintScorer s = scorer(true, 3, judge.say("짠맛", new Verdict(true, 64, ""))
                .say("소금물", new Verdict(true, 80, "")));
        assertThat(s.available()).as("아직 오늘 수를 읽기 전").isTrue();
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(64));
        assertThat(s.callsToday()).isEqualTo(3);
        assertThat(s.available()).as("상한 3 에 닿았다").isFalse();
        // 그 사이 맡겨진 쌍이 있어도 모델을 부르지 않고 실패로 끝난다
        s.request(SEA, "소금물");
        assertThat(await(s, "소금물")).isEqualTo(new LiveScore.Failed());
        assertThat(judge.calls).hasSize(1);

        // 서울 날짜가 바뀌면 다시 0 (UTC 15:00 = KST 0시)
        store.createdToday = 0;
        clock.advance(Duration.ofHours(12).toMillis());
        assertThat(s.available()).isTrue();
        s.request(SEA, "소금물");
        assertThat(await(s, "소금물")).isEqualTo(new LiveScore.Scored(80));
    }

    @Test
    void a_failure_is_not_cached_counts_toward_the_cap_and_a_new_request_asks_again() {
        AtomicInteger attempt = new AtomicInteger();
        AsyncLiveHintScorer s = scorer(true, 500, (answer, hint) -> {
            if (attempt.incrementAndGet() == 1) {
                throw new JudgeException(false, "timeout");
            }
            return new Verdict(true, 64, "");
        });
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Failed());
        assertThat(store.rows).isEmpty();
        assertThat(s.available()).as("일시적 실패는 멈추지 않는다").isTrue();
        // 무효가 된 힌트를 다시 내면 다시 묻는다
        s.request(SEA, "짠맛");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> s.poll(SEA, "짠맛")
                .filter(r -> r instanceof LiveScore.Scored).isPresent());
        assertThat(s.callsToday()).isEqualTo(2);
    }

    @Test
    void a_fatal_error_suspends_live_scoring_on_this_instance() {
        AsyncLiveHintScorer s = scorer(true, 500, (answer, hint) -> {
            throw new JudgeException(true, "live scoring rejected (401)");
        });
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Failed());
        assertThat(s.suspended()).isTrue();
        assertThat(s.available()).isFalse();
    }

    @Test
    void the_same_pair_requested_twice_runs_one_job() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AsyncLiveHintScorer s = scorer(true, 500, (answer, hint) -> {
            calls.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Verdict(true, 64, "");
        });
        s.request(SEA, "짠맛");
        s.request(SEA, "짠맛"); // 다른 방의 같은 쌍
        assertThat(s.poll(SEA, "짠맛")).as("진행 중 — 다시 걸지 않는다").isEmpty();
        release.countDown();
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(64));
        assertThat(calls).hasValue(1);
    }

    @Test
    void polling_a_pair_nobody_is_working_on_starts_the_job_again() {
        // 재시작 — 상태에는 채점 중이지만 이 인스턴스는 그 쌍을 모른다. 작업은 먼저 캐시를 읽는다(이미 매겨졌으면 호출 없음).
        store.rows.put(new Key("바다", "짠맛", "model-x", "score-v2"), new Stored(true, 64));
        AsyncLiveHintScorer s = scorer(true, 500, judge);
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(64));
        assertThat(judge.calls).isEmpty();
    }

    @Test
    void a_cache_outage_does_not_block_scoring() {
        store.broken = true;
        AsyncLiveHintScorer s = scorer(true, 500, judge.say("짠맛", new Verdict(true, 64, "")));
        s.request(SEA, "짠맛");
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(64));
    }

    @Test
    void results_expire_from_the_box_after_ten_minutes() {
        AsyncLiveHintScorer s = scorer(true, 500, judge.say("짠맛", new Verdict(true, 64, "")));
        s.request(SEA, "짠맛");
        await(s, "짠맛");
        clock.advance(AsyncLiveHintScorer.RESULT_TTL_MS + 1);
        // 결과함에서는 빠졌지만 캐시에 있다 — 다시 물으면 호출 없이 돌아온다
        assertThat(await(s, "짠맛")).isEqualTo(new LiveScore.Scored(64));
        assertThat(judge.calls).hasSize(1);
    }

    // ---- 가짜들 ----

    private static final class ScriptedJudge implements HintJudge {
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Map<String, Verdict> script = new ConcurrentHashMap<>();

        ScriptedJudge say(String hint, Verdict verdict) {
            script.put(hint, verdict);
            return this;
        }

        @Override
        public Verdict judge(AnswerEntry answer, String hint) {
            calls.add(answer.word() + "|" + answer.gloss() + "|" + hint);
            Verdict verdict = script.get(hint);
            if (verdict == null) {
                throw new JudgeException(false, "no script for " + hint);
            }
            return verdict;
        }
    }

    private static final class MemoryStore implements LiveScoreStore {
        final Map<Key, Stored> rows = new ConcurrentHashMap<>();
        final Map<Key, AtomicInteger> hits = new ConcurrentHashMap<>();
        volatile int createdToday;
        volatile boolean broken;

        @Override
        public Optional<Stored> findAndTouch(Key key) {
            if (broken) {
                throw new IllegalStateException("db down");
            }
            Stored stored = rows.get(key);
            if (stored != null) {
                hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            }
            return Optional.ofNullable(stored);
        }

        @Override
        public Stored insertFirst(Key key, Stored value) {
            if (broken) {
                throw new IllegalStateException("db down");
            }
            Stored first = rows.putIfAbsent(key, value);
            return first == null ? value : first;
        }

        @Override
        public int createdSince(Instant since) {
            if (broken) {
                throw new IllegalStateException("db down");
            }
            return createdToday;
        }
    }
}
