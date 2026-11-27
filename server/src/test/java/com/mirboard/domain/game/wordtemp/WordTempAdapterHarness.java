package com.mirboard.domain.game.wordtemp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameActionRejectedException;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.invariant.WordTempInvariantChecker;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 포트 어댑터를 <b>인프라가 부르는 순서 그대로</b> 모는 테스트 도우미(Docker 없음). 액션 경로는 {@code apply → saveState → advance},
 * 엔진 타이머 경로는 {@code EngineTimerScheduler} 와 같은 판정을 한다 — {@code timer} 가 0 보다 크면 저장·방송 없이 그만큼 기다렸다 다시 묻고,
 * 0 이하면 {@code onTimer} 를 부르는데 그때 전이가 없으면 <b>포트 계약 위반</b>으로 실패시킨다(운영에서는 타이머가 사라진다).
 */
public final class WordTempAdapterHarness {

    public static final String ROOM = "r1";
    public static final long T0 = 1_000_000L;

    /** D-323 — 받은 온도 신고. */
    public final FakeTempFeedbackStore feedback = new FakeTempFeedbackStore();
    public final List<Long> players;
    public final FakeLiveHintScorer scorer = new FakeLiveHintScorer();
    public final List<Object> published = new ArrayList<>();
    public final WordTempStateStore store = mock(WordTempStateStore.class);
    public final WordTempGameEngine adapter;
    public final WordTempConfig config;
    private final AtomicReference<WordTempState> stored = new AtomicReference<>();
    private final MutableClock clock = new MutableClock(T0);

    /** 마지막 전이에서 나간 이벤트. */
    public List<GameEvent> events = List.of();
    /** 마지막 {@link #fire} 가 전이 전에 "아직 아님"으로 다시 건 횟수(채점 폴링 횟수). */
    public int rearms;

    public WordTempAdapterHarness(int seats, Set<Integer> bots, int rounds, SimilarityTable table,
                                  ApplicationEventPublisher publisher) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            ids.add(10L + i);
        }
        this.players = List.copyOf(ids);
        GameContext ctx = new GameContext(ROOM, players, 0, 0, List.copyOf(bots),
                Map.of(RoomOption.MATCH_LENGTH, rounds == 1 ? "SHORT" : rounds == 3 ? "LONG" : "NORMAL",
                        RoomOption.BOT_LEVEL, "NORMAL"), 90);
        this.config = WordTempSettings.config(ctx, 60, Set.of("바보"), 1.0);
        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());
        this.adapter = new WordTempGameEngine(ctx, config, table, "test-table", scorer, feedback, store, clock,
                publisher != null ? publisher : published::add);
    }

    /** 사람만 4명·1라운드·고정 표, 좌석 0 이 첫 출제자이고 모두 첫 후보(좌석 0 의 정답 = 바다)를 고른 힌트 단계. */
    public static WordTempAdapterHarness hinting() {
        WordTempAdapterHarness h = new WordTempAdapterHarness(4, Set.of(), 1, WordTempFixtures.table(), null);
        h.start(0);
        for (int seat = 0; seat < 4; seat++) {
            h.act(seat, new WordTempAction.SelectWord(1, 0));
        }
        return h;
    }

    public WordTempAdapterHarness start(int firstSetter) {
        WordTempState s = adapter.rules().start(
                WordTempFixtures.candidates(config.seatCount(), config.rounds()),
                WordTempFixtures.seedStartingAt(config.seatCount(), firstSetter), now()).newState();
        stored.set(s);
        return this;
    }

    public WordTempState state() {
        return stored.get();
    }

    public long now() {
        return clock.millis();
    }

    public WordTempAdapterHarness after(long ms) {
        clock.advance(ms);
        return this;
    }

    /** 컨트롤러 경로 — 거절이면 예외가 그대로 나온다(상태는 그대로). */
    public WordTempAdapterHarness act(int seat, GameAction action) {
        GameEngine.Result result = adapter.apply(state(), seat, action);
        commit(result);
        return this;
    }

    /** 거절 코드. 거절이 아니면 실패. */
    public String rejected(int seat, GameAction action) {
        WordTempState before = state();
        try {
            adapter.apply(before, seat, action);
        } catch (GameActionRejectedException e) {
            if (state() != before) {
                throw new AssertionError("rejection changed the state");
            }
            return e.code();
        }
        throw new AssertionError("expected a rejection: " + action);
    }

    public WordTempAdapterHarness hint(String text) {
        WordTempState s = state();
        return act(s.setterSeat(), new WordTempAction.SubmitHint(s.turnNo(), s.attempt(), text));
    }

    public String hintRejected(String text) {
        WordTempState s = state();
        return rejected(s.setterSeat(), new WordTempAction.SubmitHint(s.turnNo(), s.attempt(), text));
    }

    public WordTempAdapterHarness endHints() {
        WordTempState s = state();
        return act(s.setterSeat(), new WordTempAction.EndHints(s.turnNo(), s.attempt()));
    }

    public WordTempAdapterHarness guess(int seat, String text) {
        WordTempState s = state();
        return act(seat, new WordTempAction.SubmitGuess(s.turnNo(), s.attempt(), text));
    }

    /** 인프라 턴 타임아웃 — 대기 좌석의 {@code timeoutAction} 을 적용한다. */
    public WordTempAdapterHarness timeout() {
        WordTempState s = state();
        int seat = adapter.pendingSeat(s);
        if (seat < 0) {
            events = List.of();
            return this;
        }
        return act(seat, adapter.timeoutAction(s, seat));
    }

    /**
     * 엔진 타이머가 다음 전이를 낼 때까지 — {@code EngineTimerScheduler.fire} 의 락 안 판정과 같다. 전이 하나를 내면 돌아온다.
     */
    public WordTempAdapterHarness fire() {
        rearms = 0;
        for (int i = 0; i < 10_000; i++) {
            WordTempState s = state();
            Optional<Duration> left = adapter.timer(s);
            if (left.isEmpty()) {
                throw new AssertionError("no timer declared: " + s.phase());
            }
            if (left.get().isPositive()) {
                rearms++;
                clock.advance(left.get().toMillis());
                continue; // 저장·방송 없이 다시 건다
            }
            Optional<GameEngine.Result> result = adapter.onTimer(s);
            if (result.isEmpty()) {
                throw new AssertionError("port contract: timer was due but onTimer returned nothing, phase=" + s.phase());
            }
            commit(result.get());
            return this;
        }
        throw new AssertionError("timer never fired");
    }

    /** 단계가 {@code phase} 가 될 때까지 엔진 타이머를 돌린다. */
    public WordTempAdapterHarness until(Phase phase) {
        for (int i = 0; i < 500 && state().phase() != phase; i++) {
            fire();
        }
        if (state().phase() != phase) {
            throw new AssertionError("never reached " + phase);
        }
        return this;
    }

    public GameEngine.DesertOutcome desert(int seat) {
        List<GameEvent> outbound = new ArrayList<>();
        GameEngine.DesertOutcome outcome = adapter.desert(seat, players.get(seat), outbound);
        events = List.copyOf(outbound);
        WordTempInvariantChecker.check(state(), config);
        return outcome;
    }

    private void commit(GameEngine.Result result) {
        adapter.saveState(result.newState());
        List<GameEvent> outbound = new ArrayList<>(result.events());
        adapter.advance(result.newState(), outbound);
        events = List.copyOf(outbound);
        WordTempInvariantChecker.check(state(), config);
    }

    public <T extends GameEvent> T event(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in " + events));
    }

    public boolean has(Class<? extends GameEvent> type) {
        return events.stream().anyMatch(type::isInstance);
    }

    /** 손으로 움직이는 시계. */
    public static final class MutableClock extends Clock {
        private long millis;

        public MutableClock(long millis) {
            this.millis = millis;
        }

        public void advance(long ms) {
            millis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
