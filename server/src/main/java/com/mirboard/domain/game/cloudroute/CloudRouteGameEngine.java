package com.mirboard.domain.game.cloudroute;

import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.bot.CloudRouteBotPolicy;
import com.mirboard.domain.game.cloudroute.bot.CloudRouteBotView;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent;
import com.mirboard.domain.game.cloudroute.event.CloudRouteMatchCompleted;
import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.CloudRouteStateMapper;
import com.mirboard.domain.game.cloudroute.state.MatchResult;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-212 — 구름항로의 {@link GameEngine} 포트 어댑터. 방 하나에 대응하는 per-room 인스턴스로
 * {@link CloudRouteGameDefinition#newEngine} 이 만든다. 순수 룰 엔진 {@link CloudRouteEngine}(D-211)을 감싸 상태 I/O·뷰·봇·매치 기록
 * 발행을 붙인다 — 원카드와 같은 2계층.
 *
 * <p><b>포트 확장 없음.</b> 숨길 손패가 없어 {@link #privateView} 는 empty, 시간이 지나면 저절로 일어나는 전이가 없어 엔진 타이머도
 * 기본(없음)이다. 차례 안의 결정은 액션 하나씩이라 인프라는 결정마다 턴 데드라인을 새로 건다(설계서 §6.2).
 *
 * <p>1판 = 1매치 = 1라운드. 상태를 갖지 않는다(저장소 참조만) — 동시성 직렬화는 호출자의 방 액션 락이 맡는다.
 */
public final class CloudRouteGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(CloudRouteGameEngine.class);

    private final GameContext context;
    private final CloudRouteEngine rules;
    private final CloudRouteStateStore stateStore;
    private final ApplicationEventPublisher publisher;

    public CloudRouteGameEngine(GameContext context, CloudRouteStateStore stateStore, Random random,
                                ApplicationEventPublisher publisher) {
        this.context = context;
        this.rules = new CloudRouteEngine(context, random);
        this.stateStore = stateStore;
        this.publisher = publisher;
    }

    @Override
    public GameContext context() {
        return context;
    }

    // ---------- 상태 I/O ----------

    @Override
    public Optional<GameState> loadState() {
        return stateStore.load(context.roomId()).map(GameState.class::cast);
    }

    @Override
    public void saveState(GameState state) {
        stateStore.save(context.roomId(), crState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return CloudRouteAction.class;
    }

    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        CloudRouteEngine.Result result = rules.apply(crState(state), seat, crAction(action));
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return crState(state).phaseName();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(crState(state));
    }

    /** 1판 = 1라운드 — 라운드가 끝났다는 것은 매치가 끝났다는 것이다. */
    @Override
    public boolean isRoundOver(GameState state) {
        return crState(state).ended();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(CloudRouteState::ended).orElse(false);
    }

    // ---------- 뷰 ----------

    /** 편지 더미 순서만 빼고 전부 공개(룰 §13). */
    @Override
    public Object publicView(GameState state) {
        return CloudRouteStateMapper.toTableView(crState(state));
    }

    /** 본인만 아는 정보가 없다 — State Hiding 은 "있으면 본인 큐로만"이지 "반드시 있다"가 아니다(`docs/game-port.md` §1). */
    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.empty();
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(crState(state), seat));
    }

    /** 휴리스틱 {@link CloudRouteBotPolicy} — 공개 정보 뷰만 본다. 결정적이라 {@code random} 은 쓰지 않는다. */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        return chooseBotAction(crState(state), seat, CloudRouteBotPolicy::choose);
    }

    /**
     * 정책 호출 + 안전망. 둘 수 없으면(남의 차례·끝난 판) null. 정책이 예외를 던지거나 null·합법수 밖 액션을 내면 ERROR 로그 후
     * 시간 초과 행동으로 떨어진다 — 턴 제한이 꺼진 방에서 정책 버그 하나로 방이 멈추지 않게(원카드·스컬킹과 같은 안전망).
     */
    GameAction chooseBotAction(CloudRouteState state, int seat,
                               BiFunction<CloudRouteBotView, List<CloudRouteAction>, CloudRouteAction> policy) {
        if (!rules.pendingSeats(state).contains(seat)) {
            return null;
        }
        List<CloudRouteAction> legal = rules.legalActions(state, seat);
        try {
            CloudRouteAction chosen = policy.apply(CloudRouteBotView.of(state, seat), legal);
            if (chosen != null && legal.contains(chosen)) {
                return chosen;
            }
            log.error("CloudRoute bot policy returned a non-legal action, falling back: room={} seat={} action={}",
                    context.roomId(), seat, chosen);
        } catch (RuntimeException e) {
            log.error("CloudRoute bot policy failed, falling back: room={} seat={}", context.roomId(), seat, e);
        }
        return rules.timeoutAction(state, seat);
    }

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.timeoutAction(crState(state), seat);
    }

    // ---------- 매치 진행 ----------

    /** 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED}) 기록 이벤트를 한 번 발행한다. */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        CloudRouteState state = crState(newState);
        boolean endedNow = state.ended()
                && outbound.stream().anyMatch(CloudRouteEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state.result());
        return new Advance(true, true);
    }

    /** 탈주 (룰 §11) — 파산과 같은 탈락 경로. 이미 끝났거나 이미 탈락한 좌석이면 상태 무변경으로 {@code NOT_APPLICABLE}. */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        CloudRouteState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        CloudRouteEngine.Desertion desertion = rules.desert(state, seat);
        switch (desertion.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case CONTINUED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                log.warn("CloudRoute desertion continued: room={} seat={} userId={}",
                        context.roomId(), seat, deserterUserId);
                return DesertOutcome.MATCH_CONTINUES;
            }
            case MATCH_ENDED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                record(desertion.newState().result());
                log.warn("CloudRoute desertion ended match: room={} seat={} userId={} reason={}",
                        context.roomId(), seat, deserterUserId, desertion.newState().result().reason());
                return DesertOutcome.MATCH_ENDED;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /**
     * 로컬 발행 — 기록기({@code CloudRouteMatchRecorder}, 동기 {@code @Transactional} 리스너 하나)가 듣는다. 기록 실패는 진행으로 새지
     * 않는다(`docs/game-port.md` "매치 기록기 계약" 3 — 리스너가 하나면 발행 지점에서 잡아 결과를 실은 ERROR 로).
     */
    private void record(MatchResult result) {
        try {
            publisher.publishEvent(new CloudRouteMatchCompleted(context.roomId(), context.playerIds(), result));
        } catch (RuntimeException e) {
            log.error("CloudRoute match record failed, the match still ends: room={} players={} result={}",
                    context.roomId(), context.playerIds(), result, e);
        }
        log.info("CloudRoute match ended: room={} reason={} rounds={} winners={}",
                context.roomId(), result.reason(), result.rounds(), result.winners());
    }

    private static CloudRouteState crState(GameState state) {
        if (state instanceof CloudRouteState cr) {
            return cr;
        }
        throw new IllegalArgumentException("Not a CloudRouteState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static CloudRouteAction crAction(GameAction action) {
        if (action instanceof CloudRouteAction cr) {
            return cr;
        }
        throw new IllegalArgumentException("Not a CloudRouteAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
