package com.mirboard.domain.game.starsquare;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.starsquare.action.StarSquareAction;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.event.StarSquareMatchCompleted;
import com.mirboard.domain.game.starsquare.persistence.StarSquareStateStore;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper;
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
 * 별자리 수판의 {@link GameEngine} 포트 어댑터(S2). 방 하나에 대응하는 per-room 인스턴스로 {@link StarSquareGameDefinition#newEngine}
 * 이 만든다. 순수 룰 엔진 {@link StarSquareEngine}(S1)을 감싸 상태 I/O·시계·뷰·매치 기록 발행을 붙인다(D-98 2계층).
 *
 * <ul>
 *   <li><b>동시 진행</b> — 차례가 없다. 대기 좌석(포트 ④)은 푸는 단계의 살아 있는 미완성 <b>사람</b>이고, 봇은 넣지 않는다(봇은 수를 고르지
 *       않고 엔진 타이머가 페이스대로 채운다 — 룰 §10).</li>
 *   <li><b>시간</b> — 준비 끝·봇 이정표·마감·결과 보기 끝은 전부 포트 ⑦ 엔진 타이머다. 시작 시각을 상태에 두므로 {@link #timer} 는 재무장해도
 *       처음부터 세지 않는다. 인프라 턴 데드라인(마지막 액션 + 퍼즐 제한)은 늘 더 늦어 {@link #timeoutAction} 은 폴백으로만 남는다(D-183).</li>
 *   <li><b>은닉</b> — 공개 뷰·공개 이벤트 타입에는 정답·푸는 중인 남의 판 필드가 없다. 내 판은 {@link #privateView} 로만(룰 §11). 이 퍼즐을
 *       완성한 좌석만 아직 푸는 남의 판을 받는다 — 비공개 뷰의 {@code peers} 와 비공개 이벤트 {@code PEER_BOARD}(D-294).</li>
 * </ul>
 */
public final class StarSquareGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(StarSquareGameEngine.class);

    private final GameContext context;
    private final StarSquareConfig config;
    private final StarSquareEngine rules;
    private final StarSquareStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public StarSquareGameEngine(GameContext context,
                                StarSquareConfig config,
                                StarSquareStateStore stateStore,
                                Clock clock,
                                ApplicationEventPublisher publisher) {
        this.context = context;
        this.config = config;
        this.rules = new StarSquareEngine(config);
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Override
    public GameContext context() {
        return context;
    }

    StarSquareEngine rules() {
        return rules;
    }

    // ---------- 상태 I/O ----------

    @Override
    public Optional<GameState> loadState() {
        return stateStore.load(context.roomId()).map(GameState.class::cast);
    }

    @Override
    public void saveState(GameState state) {
        stateStore.save(context.roomId(), sState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return StarSquareAction.class;
    }

    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        StarSquareEngine.Result result = rules.apply(sState(state), seat, sAction(action), clock.millis());
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return sState(state).phase().name();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(sState(state));
    }

    /** 1판 = 1매치 — 퍼즐 R개는 엔진 안의 숫자다. */
    @Override
    public boolean isRoundOver(GameState state) {
        return sState(state).ended();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(StarSquareState::ended).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        StarSquareState s = sState(state);
        return StarSquareStateMapper.toPublicView(s, rules.remainingMillis(s, clock.millis()));
    }

    /** 내 판(퍼즐 번호·판·버전·완성 + 완성했으면 아직 푸는 남의 판) — 준비 단계·봇·범위 밖 좌석(관전자)은 없다. */
    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return StarSquareStateMapper.toPrivateBoard(sState(state), seat, config.botSeats()).map(Object.class::cast);
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(sState(state), seat));
    }

    /**
     * 봇은 대기 좌석이 아니라 인프라 봇 루프가 부르지 않는다(봇 진행은 엔진 타이머 — 룰 §10). 그래도 불리면 대기 좌석일 때만 폴백(무변화
     * {@code SYNC}), 아니면 null(둘 것 없음).
     */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        StarSquareState s = sState(state);
        return rules.pendingSeats(s).contains(seat) ? rules.fallbackAction(s, seat) : null;
    }

    /** 폴백(룰 §9-6) — 저장된 판·버전 그대로의 {@code SYNC}. 받되 무변화라 판을 바꾸지 않는다. */
    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.fallbackAction(sState(state), seat);
    }

    // ---------- 엔진 타이머 (포트 ⑦) ----------

    /** 다음 시간 전이(준비 끝·봇 이정표·마감·결과 보기 끝)까지 남은 시간(지났으면 0). 끝난 매치면 없음. */
    @Override
    public Optional<Duration> timer(GameState state) {
        OptionalLong remaining = rules.timer(sState(state), clock.millis());
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.max(0L, remaining.getAsLong())));
    }

    /** 닿은 시간 전이를 모두 처리한다. 인프라가 락 안에서 만기를 다시 확인한 뒤 부른다 — 닿은 것이 없으면 없음. */
    @Override
    public Optional<Result> onTimer(GameState state) {
        StarSquareState s = sState(state);
        if (s.ended()) {
            return Optional.empty();
        }
        StarSquareEngine.Result result = rules.onTimer(s, clock.millis());
        if (result.events().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Result(result.newState(), List.<GameEvent>copyOf(result.events())));
    }

    // ---------- 매치 진행 ----------

    /** 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED} 가 있으면) 기록 이벤트를 한 번 발행한다. 퍼즐 끝은 엔진 안의 일이다. */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        StarSquareState state = sState(newState);
        boolean endedNow = state.ended() && outbound.stream().anyMatch(StarSquareEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state.result());
        return new Advance(true, true);
    }

    /** 탈주(룰 §12.1) — 순수 엔진의 3치 결과를 포트 3치로 옮긴다. */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        StarSquareState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        StarSquareEngine.Desertion desertion = rules.desert(state, seat, clock.millis());
        switch (desertion.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case MATCH_CONTINUES -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                log.warn("Star square desertion continued: room={} seat={} userId={}", context.roomId(), seat,
                        deserterUserId);
                return DesertOutcome.MATCH_CONTINUES;
            }
            case MATCH_ENDED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                record(desertion.newState().result());
                log.warn("Star square desertion ended match: room={} seat={} userId={} reason={}",
                        context.roomId(), seat, deserterUserId, desertion.newState().result().reason());
                return DesertOutcome.MATCH_ENDED;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /**
     * 로컬 발행 — 기록기({@code StarSquareMatchRecorder}) 하나가 듣는다. 실패를 여기서 잡아 결과를 실은 ERROR 로 남기고 진행은 계속한다
     * (`docs/game-port.md` "매치 기록기 계약" 3, 얏추·원카드와 같다).
     */
    private void record(MatchResult result) {
        try {
            publisher.publishEvent(new StarSquareMatchCompleted(context.roomId(), context.playerIds(), result));
        } catch (RuntimeException e) {
            log.error("Star square match record failed, the match still ends: room={} players={} result={}",
                    context.roomId(), context.playerIds(), result, e);
        }
        log.info("Star square match ended: room={} reason={} winners={}", context.roomId(), result.reason(),
                result.winners());
    }

    private static StarSquareState sState(GameState state) {
        if (state instanceof StarSquareState s) {
            return s;
        }
        throw new IllegalArgumentException("Not a StarSquareState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static StarSquareAction sAction(GameAction action) {
        if (action instanceof StarSquareAction a) {
            return a;
        }
        throw new IllegalArgumentException("Not a StarSquareAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
