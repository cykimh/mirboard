package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.feedback.TempFeedbackStore;
import com.mirboard.domain.game.wordtemp.event.WordTempMatchCompleted;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.scoring.LiveHintScorer;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintSource;
import com.mirboard.domain.game.wordtemp.state.WordTempViews;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 낱말 온도의 {@link GameEngine} 포트 어댑터(S2, D-316·D-317). 방 하나에 대응하는 per-room 인스턴스로
 * {@link WordTempGameDefinition#newEngine} 이 만든다. 순수 룰 엔진 {@link WordTempEngine}(S1b)을 감싸 상태 I/O·시계·뷰·실시간 채점
 * 요청과 결과 수거·매치 기록 발행을 붙인다(D-98 2계층). <b>포트 변경 0</b> — 전부 기존 계약 안이다.
 *
 * <ul>
 *   <li><b>대기 좌석(④)</b> — 그 단계에서 할 일이 남은 살아 있는 <b>사람</b>뿐. 봇은 넣지 않는다 — 봇의 힌트·추리는 상태의 {@code botPlan}
 *       시각에 엔진 타이머가 실행한다(룰 §11). 그래서 인프라 봇 루프는 이 게임에 들어오지 않고 {@link #botAction} 은 쓰이지 않는다.</li>
 *   <li><b>시간(⑦)</b> — 단계 마감·봇 행동·채점 기다림 상한은 순수 엔진의 {@code timer}/{@code onTimer} 이고, 어댑터는 거기에
 *       <b>채점 결과 폴링</b>을 합친다: 채점 중인 힌트가 있으면 결과함에 결과가 왔을 때 0, 아니면 {@value #POLL_MS}ms. 인프라는 0 보다 크면
 *       저장·방송 없이 그 시간 뒤에 다시 걸고(D-128 "락 안 재확인"), 0 이하면 {@link #onTimer} 를 부른다 — 결과가 있으면
 *       {@code applyScore}, 없으면 엔진의 시간 전이(8초 상한이면 무효). 잠금 안에서 네트워크·DB 를 기다리지 않는다(D-312).</li>
 *   <li><b>타임아웃(⑥)</b> — 인프라 턴 데드라인(마지막 액션 + 힌트 제한)은 늘 단계 마감보다 늦거나 같다. {@link #timeoutAction} 은 멱등
 *       {@code TICK{turnNo, attempt, phase}} — 그 단계의 마감이 지났을 때만 엔진 타이머와 같은 전이를 한다(룰 §9).</li>
 *   <li><b>은닉(③)</b> — 공개 뷰·공개 이벤트 타입에는 후보·진행 중인 정답·미공개 힌트·공개 전 추리 필드가 없다. 본인 것만
 *       {@link #privateView} 로(판정은 {@code WordTempVisibility} 한 곳). 비공개 이벤트는 순번을 쓰지 않는다(D-126).</li>
 * </ul>
 */
public final class WordTempGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(WordTempGameEngine.class);

    /** 채점 결과 폴링 간격(설계서 §4.2 ③). */
    static final long POLL_MS = 250L;

    private final GameContext context;
    private final WordTempConfig config;
    private final WordTempEngine rules;
    private final String tableVersion;
    private final LiveHintScorer scorer;
    private final WordTempStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final TempFeedbackStore feedback;

    public WordTempGameEngine(GameContext context,
                              WordTempConfig config,
                              SimilarityTable table,
                              String tableVersion,
                              LiveHintScorer scorer,
                              TempFeedbackStore feedback,
                              WordTempStateStore stateStore,
                              Clock clock,
                              ApplicationEventPublisher publisher) {
        this.context = context;
        this.config = config;
        this.rules = new WordTempEngine(config, table, (what, fault) ->
                // D-118·D-119 — 정책 결함은 조용히 넘기지 않는다. 엔진은 그 좌석을 [힌트 끝]·패스로 넘기고 판은 계속된다.
                log.error("Word temperature bot policy failed, falling back: room={} {}", context.roomId(), what, fault));
        this.tableVersion = tableVersion;
        this.scorer = scorer;
        this.feedback = feedback;
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Override
    public GameContext context() {
        return context;
    }

    WordTempEngine rules() {
        return rules;
    }

    // ---------- 상태 I/O ----------

    @Override
    public Optional<GameState> loadState() {
        return stateStore.load(context.roomId()).map(GameState.class::cast);
    }

    @Override
    public void saveState(GameState state) {
        stateStore.save(context.roomId(), wState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return WordTempAction.class;
    }

    /**
     * 표 조회는 메모리에서 끝난다. 표 밖 힌트는 실시간 채점을 맡길 수 있을 때만 <b>채점 중</b>으로 들어가고(아니면 {@code UNKNOWN_WORD} —
     * 개수 유지), 그때 채점기에 쌍을 맡기고 바로 돌아온다 — 호출 결과는 엔진 타이머가 가져온다.
     */
    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        WordTempState before = wState(state);
        WordTempAction wAction = wAction(action);
        boolean liveAvailable = wAction instanceof WordTempAction.SubmitHint && scorer.available();
        WordTempEngine.Result result = rules.apply(before, seat, wAction, clock.millis(), liveAvailable);
        Hint pending = result.newState().pendingHint();
        if (pending != null && before.pendingHint() == null) {
            // 상태 저장이 실패해도 요청만 나간 것 — 결과는 캐시에 남아 다음에 쓰인다(설계서 §4.4)
            scorer.request(result.newState().answer(), pending.text());
        }
        recordFlags(before, result.events());
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    /**
     * 받아들인 "온도가 이상해요"(D-323)를 낱말만 남긴다 — (정답, 힌트, 방향, 온도, 버전). 누가·어느 방에서 냈는지는 넘기지 않는다.
     * 저장 실패는 판을 막지 않는다(신고는 부가 기능이다).
     */
    private void recordFlags(WordTempState before, List<WordTempEvent> events) {
        for (WordTempEvent event : events) {
            if (!(event instanceof WordTempEvent.FlagAccepted flag) || before.answer() == null) {
                continue;
            }
            try {
                String version = before.hints().stream().filter(h -> h.text().equals(flag.text())).findFirst()
                        .filter(h -> h.source() == HintSource.LIVE).map(h -> "live").orElse(tableVersion);
                feedback.record(before.answer().word(), flag.text(), flag.direction().name(), flag.temp(), version);
            } catch (RuntimeException e) {
                log.error("Word temperature feedback not recorded: room={}", context.roomId(), e);
            }
        }
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return wState(state).phase().name();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(wState(state));
    }

    /** 1판 = 1매치 — 라운드·출제 차례는 엔진 안의 숫자다. */
    @Override
    public boolean isRoundOver(GameState state) {
        return wState(state).ended();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(WordTempState::ended).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        return WordTempViews.publicView(wState(state), config, clock.millis());
    }

    /** 내 후보·고른 낱말, (출제자면) 이번 차례의 힌트·온도·채점 중 표시, (추리자면) 낸 답. 봇·빠진 좌석·관전자는 없다. */
    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.ofNullable(WordTempViews.privateView(wState(state), config, seat));
    }

    // ---------- 봇 / 타임아웃 ----------

    /** 고를 수 있는 수를 늘어놓을 수 없는 게임이다(낱말 입력) — 빈 목록. 인프라는 {@link #botAction}·{@link #timeoutAction} 만 쓴다. */
    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.of();
    }

    /**
     * 봇 좌석은 대기 좌석이 아니라 인프라 봇 루프가 부르지 않는다 — 봇의 수는 순수 봇 정책({@code WordTempBotPolicy})이 정하고 엔진
     * 타이머가 실행한다(정책이 예외·비합법 수를 내면 ERROR 로그 뒤 [힌트 끝]·패스 — D-118·D-119, 생성자의 {@code botFault}). 그래도 불리면
     * 대기 좌석일 때만 멱등 {@code TICK}({@link #timeoutAction}), 아니면 null(둘 것 없음).
     */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        WordTempState s = wState(state);
        return rules.pendingSeats(s).contains(seat) ? timeoutAction(s, seat) : null;
    }

    /** 멱등 {@code TICK}(룰 §9) — 지금 단계가 그 단계이고 마감이 지났을 때만 엔진 타이머의 마감 전이를 한다. 아니면 무변경. */
    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        WordTempState s = wState(state);
        return new WordTempAction.Tick(s.turnNo(), s.attempt(), s.phase());
    }

    // ---------- 엔진 타이머 (포트 ⑦) ----------

    /**
     * 다음에 시간이 일으킬 일까지 남은 시간 — 단계 마감·봇 행동·채점 기다림 상한(순수 엔진) 그리고 채점 결과 폴링 가운데 가장 이른 것.
     * 결과함은 메모리라 여기서 기다리는 것이 없다. 끝난 매치면 없음.
     */
    @Override
    public Optional<Duration> timer(GameState state) {
        WordTempState s = wState(state);
        OptionalLong left = rules.timer(s, clock.millis());
        if (left.isEmpty()) {
            return Optional.empty();
        }
        long ms = Math.max(0L, left.getAsLong());
        Hint pending = scoringHint(s);
        if (pending != null) {
            ms = scorer.poll(s.answer(), pending.text()).isPresent() ? 0L : Math.min(ms, POLL_MS);
        }
        return Optional.of(Duration.ofMillis(ms));
    }

    /**
     * 만기된 것 하나 — 채점 결과 반영이 먼저다(결과가 와 있으면 기다림 상한이 같이 지났어도 결과를 쓴다). 그다음은 순수 엔진의 순서
     * (기다림 상한 → 봇 행동 → 단계 마감). 닿은 것이 없으면 없음.
     */
    @Override
    public Optional<Result> onTimer(GameState state) {
        WordTempState s = wState(state);
        if (s.ended()) {
            return Optional.empty();
        }
        long now = clock.millis();
        WordTempEngine.Result result = null;
        Hint pending = scoringHint(s);
        if (pending != null) {
            Optional<LiveScore> score = scorer.poll(s.answer(), pending.text());
            if (score.isPresent()) {
                result = rules.applyScore(s, pending.text(), score.get(), now);
            }
        }
        if (result == null) {
            result = rules.onTimer(s, now);
        }
        if (result.events().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Result(result.newState(), List.<GameEvent>copyOf(result.events())));
    }

    /** 지금 채점을 기다리는 힌트(힌트 단계에만 있다). */
    private static Hint scoringHint(WordTempState s) {
        return s.ended() || s.phase() != Phase.HINT ? null : s.pendingHint();
    }

    // ---------- 매치 진행 ----------

    /** 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED} 가 있으면) 기록 이벤트를 한 번 발행한다. 라운드 끝은 엔진 안의 일이다. */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        WordTempState state = wState(newState);
        boolean endedNow = state.ended() && outbound.stream().anyMatch(WordTempEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state);
        return new Advance(true, true);
    }

    /** 탈주·강퇴·유예 만료(룰 §10) — 순수 엔진의 3치 결과를 포트 3치로 옮긴다. 채점 중이던 힌트는 엔진이 버린다. */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        WordTempState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        WordTempEngine.Desertion desertion = rules.desert(state, seat, clock.millis());
        switch (desertion.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case MATCH_CONTINUES -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                log.warn("Word temperature desertion continued: room={} seat={} userId={}", context.roomId(), seat,
                        deserterUserId);
                return DesertOutcome.MATCH_CONTINUES;
            }
            case MATCH_ENDED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                record(desertion.newState());
                log.warn("Word temperature desertion ended match: room={} seat={} userId={} reason={}",
                        context.roomId(), seat, deserterUserId, desertion.newState().result().endReason());
                return DesertOutcome.MATCH_ENDED;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /**
     * 로컬 발행 — 기록기({@code WordTempMatchRecorder}) 하나가 듣는다. 실패를 여기서 잡아 결과를 실은 ERROR 로 남기고 진행은 계속한다
     * (`docs/game-port.md` "매치 기록기 계약" 3).
     */
    private void record(WordTempState ended) {
        try {
            publisher.publishEvent(new WordTempMatchCompleted(context.roomId(), context.playerIds(), ended.result(),
                    ended.history(), tableVersion));
        } catch (RuntimeException e) {
            log.error("Word temperature match record failed, the match still ends: room={} players={} result={}",
                    context.roomId(), context.playerIds(), ended.result(), e);
        }
        log.info("Word temperature match ended: room={} reason={} turns={} liveRequests={}", context.roomId(),
                ended.result().endReason(), ended.history().size(), ended.liveRequests());
    }

    private static WordTempState wState(GameState state) {
        if (state instanceof WordTempState s) {
            return s;
        }
        throw new IllegalArgumentException("Not a WordTempState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static WordTempAction wAction(GameAction action) {
        if (action instanceof WordTempAction a) {
            return a;
        }
        throw new IllegalArgumentException("Not a WordTempAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
