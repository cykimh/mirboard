package com.mirboard.domain.game.wordtemp.scoring;

import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.scoring.HintJudge.JudgeException;
import com.mirboard.domain.game.wordtemp.scoring.HintJudge.Verdict;
import com.mirboard.domain.game.wordtemp.scoring.LiveScoreStore.Key;
import com.mirboard.domain.game.wordtemp.scoring.LiveScoreStore.Stored;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 실시간 채점기(D-312·D-317, 설계서 §4.2 ②) — 맡은 쌍을 <b>가상 스레드</b>에서 채점하고 결과를 메모리 결과함에 넣는다. 포트 어댑터의 엔진
 * 타이머가 약 250ms 마다 {@link #poll} 로 결과함을 본다. 방·사용자를 모른다.
 *
 * <p>한 쌍의 작업: ① Postgres 캐시를 읽는다(있으면 그 값 — 호출 없음, {@code hits} +1) → ② 없으면 하루 상한을 세고 동시 실행 칸
 * (기본 2)을 얻어 모델을 부른다 → ③ 결과를 캐시에 처음 쓴 값으로 넣고 다시 읽은 값을 결과로 삼는다(먼저 쓴 쪽이 이긴다). 실패·시간
 * 초과·거절은 "실패"이고 캐시에 쓰지 않는다.
 *
 * <p><b>안전장치</b>(전부 여기 한 곳):
 * <ul>
 *   <li>끄기 스위치 {@code mirboard.wordtemp.live-scoring.enabled}(기본 false) · API 키 없음 → {@link #available} false. 게임은 표만으로 진행.</li>
 *   <li>하루(Asia/Seoul) 호출 상한 {@code daily-cap}(기본 600) — 오늘 캐시에 새로 쓰인 행 수(재시작 뒤에도 이어짐) + 이 인스턴스의 호출 수.</li>
 *   <li>400·401·403·404 → ERROR 로그 뒤 이 인스턴스에서 실시간 채점 일시 중지(재배포·재시작 때 풀린다).</li>
 *   <li>같은 쌍은 작업 하나로 합친다(진행 중 맵).</li>
 * </ul>
 */
@Component
public class AsyncLiveHintScorer implements LiveHintScorer {

    private static final Logger log = LoggerFactory.getLogger(AsyncLiveHintScorer.class);
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Seoul");
    /** 결과함에 결과를 두는 시간 — 엔진의 기다림 상한(8초)보다 훨씬 길어야 한다. */
    static final long RESULT_TTL_MS = Duration.ofMinutes(10).toMillis();
    /**
     * 방금 난 실패는 이만큼 그대로 둔다 — 같은 쌍을 기다리던 다른 방의 {@code timer()}(0)와 {@code onTimer()} 사이에 새 요청이 실패를
     * 지우면 그 방의 onTimer 가 빈손이 되어 엔진 타이머가 다시 걸리지 않는다(리뷰 지적). 폴링 주기(250ms)보다 넉넉히.
     */
    static final long FAILED_HOLD_MS = 2_000;
    /** 동시 실행 칸을 기다리는 상한 — 이보다 늦게 시작하면 어차피 기다림 상한(8초) 안에 못 끝난다. */
    private static final long SLOT_WAIT_MS = 2_500L;
    private static final int RESULT_SWEEP_SIZE = 512;

    private final boolean enabled;
    private final String model;
    private final String promptVersion;
    private final int dailyCap;
    private final HintJudge judge;
    private final LiveScoreStore store;
    private final Clock clock;
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<Pair, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Pair, Entry> results = new ConcurrentHashMap<>();
    private final Object dayLock = new Object();
    private LocalDate day;
    private int callsToday;
    private boolean dayLoaded;
    private volatile boolean suspended;

    private record Pair(String answer, String hint) {
    }

    private record Entry(LiveScore score, long at) {
    }

    @Autowired
    public AsyncLiveHintScorer(@Value("${mirboard.wordtemp.live-scoring.enabled:false}") boolean enabled,
                               @Value("${mirboard.wordtemp.live-scoring.api-key:}") String apiKey,
                               @Value("${mirboard.wordtemp.model:claude-sonnet-5-5}") String model,
                               @Value("${mirboard.wordtemp.live-scoring.prompt-version:score-v2}") String promptVersion,
                               @Value("${mirboard.wordtemp.live-scoring.daily-cap:600}") int dailyCap,
                               @Value("${mirboard.wordtemp.live-scoring.max-concurrent:2}") int maxConcurrent,
                               @Value("${mirboard.wordtemp.live-scoring.timeout-ms:5000}") long timeoutMs,
                               LiveScoreStore store,
                               Clock clock) {
        this(enabled, model, promptVersion, dailyCap, maxConcurrent,
                enabled && apiKey != null && !apiKey.isBlank()
                        ? new ClaudeHintJudge(apiKey.strip(), model, promptVersion, Duration.ofMillis(timeoutMs)) : null,
                store, clock);
        if (!enabled) {
            log.info("Word temperature live scoring is OFF (mirboard.wordtemp.live-scoring.enabled=false) — table only");
        } else if (judge == null) {
            log.warn("Word temperature live scoring is enabled but ANTHROPIC_API_KEY is not set — table only");
        } else {
            log.info("Word temperature live scoring is ON: model={} prompt={} dailyCap={} maxConcurrent={} timeoutMs={}",
                    model, promptVersion, dailyCap, maxConcurrent, timeoutMs);
        }
    }

    /** 테스트·조립용 — {@code judge} 가 null 이면 꺼진 것과 같다. */
    public AsyncLiveHintScorer(boolean enabled, String model, String promptVersion, int dailyCap, int maxConcurrent,
                               HintJudge judge, LiveScoreStore store, Clock clock) {
        this.enabled = enabled;
        this.model = model;
        this.promptVersion = promptVersion;
        this.dailyCap = dailyCap;
        this.judge = judge;
        this.store = store;
        this.clock = clock;
        this.slots = new Semaphore(Math.max(1, maxConcurrent));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---- LiveHintScorer (잠금 안 — 메모리만) ----

    @Override
    public boolean available() {
        if (!enabled || judge == null || suspended) {
            return false;
        }
        synchronized (dayLock) {
            rollDay();
            return callsToday < dailyCap;
        }
    }

    @Override
    public void request(AnswerEntry answer, String hint) {
        Pair pair = new Pair(answer.word(), hint);
        Entry known = fresh(pair);
        if (known != null && !(known.score() instanceof LiveScore.Failed)) {
            return; // 방금 매긴 쌍 — 다음 폴링이 바로 가져간다
        }
        if (known != null && clock.millis() - known.at() < FAILED_HOLD_MS) {
            return; // 방금 난 실패 — 다른 방이 아직 가져가는 중일 수 있다. 이 요청도 같은 실패를 받는다(무효·개수 돌려줌)
        }
        if (known != null) {
            results.remove(pair, known); // 지난 실패는 지우고 다시 묻는다(무효가 된 힌트는 다시 낼 수 있다 — 룰 §5.2)
        }
        submit(pair, answer);
    }

    @Override
    public Optional<LiveScore> poll(AnswerEntry answer, String hint) {
        Pair pair = new Pair(answer.word(), hint);
        Entry known = fresh(pair);
        if (known != null) {
            return Optional.of(known.score());
        }
        if (inFlight.containsKey(pair)) {
            return Optional.empty();
        }
        known = fresh(pair); // 방금 끝났을 수 있다
        if (known != null) {
            return Optional.of(known.score());
        }
        // 결과도 작업도 없다 — 재시작으로 잃었거나 다른 인스턴스가 맡았다(설계서 §4.4). 맡길 수 있으면 다시 걸고(작업은 먼저 캐시를 읽는다),
        // 없으면 실패로 풀어 준다. 실패도 결과함에 넣는다 — 같은 답이 이어서 나와야 한다.
        if (!available()) {
            Entry failed = new Entry(new LiveScore.Failed(), clock.millis());
            results.put(pair, failed);
            return Optional.of(failed.score());
        }
        submit(pair, answer);
        return Optional.empty();
    }

    private Entry fresh(Pair pair) {
        Entry entry = results.get(pair);
        if (entry == null) {
            return null;
        }
        if (clock.millis() - entry.at() > RESULT_TTL_MS) {
            results.remove(pair, entry);
            return null;
        }
        return entry;
    }

    private void submit(Pair pair, AnswerEntry answer) {
        if (inFlight.putIfAbsent(pair, Boolean.TRUE) != null) {
            return;
        }
        try {
            executor.execute(() -> {
                LiveScore score;
                try {
                    score = score(answer, pair.hint());
                } catch (Throwable unexpected) {
                    log.error("Word temperature live scoring crashed: answer={} hint={}", pair.answer(), pair.hint(),
                            unexpected);
                    score = new LiveScore.Failed();
                }
                // 결과를 먼저 넣고 진행 중 표시를 지운다 — 폴링이 "둘 다 없음"을 보지 않게.
                results.put(pair, new Entry(score, clock.millis()));
                inFlight.remove(pair);
                sweep();
            });
        } catch (RuntimeException rejected) {
            // 종료 중 — 실패로 풀어 준다
            results.put(pair, new Entry(new LiveScore.Failed(), clock.millis()));
            inFlight.remove(pair);
        }
    }

    private void sweep() {
        if (results.size() < RESULT_SWEEP_SIZE) {
            return;
        }
        long now = clock.millis();
        results.entrySet().removeIf(e -> now - e.getValue().at() > RESULT_TTL_MS);
    }

    // ---- 한 쌍의 작업 (가상 스레드 — 잠금 밖) ----

    LiveScore score(AnswerEntry answer, String hint) {
        Key key = new Key(answer.word(), hint, model, promptVersion);
        try {
            Optional<Stored> cached = store.findAndTouch(key);
            if (cached.isPresent()) {
                return toScore(cached.get());
            }
        } catch (RuntimeException e) {
            log.warn("Word temperature live score cache read failed, asking the model: {}", e.toString());
        }
        if (!enabled || judge == null || suspended || !countCall()) {
            return new LiveScore.Failed();
        }
        boolean slot;
        try {
            slot = slots.tryAcquire(SLOT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LiveScore.Failed();
        }
        if (!slot) {
            log.warn("Word temperature live scoring is saturated — hint voided");
            return new LiveScore.Failed();
        }
        Verdict verdict;
        long started = System.nanoTime();
        try {
            verdict = judge.judge(answer, hint);
        } catch (JudgeException e) {
            if (e.fatal()) {
                suspended = true;
                log.error("Word temperature live scoring SUSPENDED on this instance (check ANTHROPIC_API_KEY and"
                        + " mirboard.wordtemp.model={}): {}", model, e.getMessage(), e);
            } else {
                log.warn("Word temperature live scoring failed: {} ({}ms)", e.getMessage(),
                        (System.nanoTime() - started) / 1_000_000);
            }
            return new LiveScore.Failed();
        } finally {
            slots.release();
        }
        Stored value = new Stored(verdict.validNoun(), verdict.validNoun() ? clamp(verdict.score()) : 0);
        log.info("Word temperature live score: answer={} hint={} valid={} score={} {}ms reason={}", answer.word(),
                hint, value.valid(), value.score(), (System.nanoTime() - started) / 1_000_000, verdict.reason());
        try {
            value = store.insertFirst(key, value);
        } catch (RuntimeException e) {
            log.warn("Word temperature live score cache write failed (score still used): {}", e.toString());
        }
        return toScore(value);
    }

    private static LiveScore toScore(Stored stored) {
        return stored.valid() ? new LiveScore.Scored(clamp(stored.score())) : new LiveScore.NotANoun();
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /** 하루 상한 안이면 한 번 세고 true. 그날 처음이면 캐시 표에서 오늘 쓴 행 수를 읽어 잇는다(머신이 자주 잠든다 — D-171). */
    private boolean countCall() {
        LocalDate toLoad = null;
        synchronized (dayLock) {
            rollDay();
            if (!dayLoaded) {
                dayLoaded = true;
                toLoad = day;
            }
        }
        if (toLoad != null) {
            // DB 읽기는 dayLock 밖에서 — available() 이 방 잠금 안에서 이 잠금을 잡는다.
            int already = 0;
            try {
                already = store.createdSince(toLoad.atStartOfDay(DAY_ZONE).toInstant());
            } catch (RuntimeException e) {
                log.warn("Word temperature live score daily count unavailable, counting from this instance only: {}",
                        e.toString());
            }
            synchronized (dayLock) {
                if (toLoad.equals(day)) {
                    callsToday += already;
                }
            }
        }
        synchronized (dayLock) {
            rollDay();
            if (callsToday >= dailyCap) {
                log.warn("Word temperature live scoring daily cap reached: {}/{}", callsToday, dailyCap);
                return false;
            }
            callsToday++;
            return true;
        }
    }

    private void rollDay() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), DAY_ZONE);
        if (!today.equals(day)) {
            day = today;
            callsToday = 0;
            dayLoaded = false;
        }
    }

    /** 오늘 센 호출 수(테스트·점검용). */
    int callsToday() {
        synchronized (dayLock) {
            rollDay();
            return callsToday;
        }
    }

    boolean suspended() {
        return suspended;
    }
}
