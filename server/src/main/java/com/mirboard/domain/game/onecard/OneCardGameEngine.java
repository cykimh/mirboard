package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.bot.OneCardBotPolicy;
import com.mirboard.domain.game.onecard.bot.OneCardBotView;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-128 — 원카드의 {@link GameEngine} 포트 어댑터. 방 하나에 대응하는 per-room 인스턴스로
 * {@link OneCardGameDefinition#newEngine} 이 만든다. 순수 룰 엔진 {@link OneCardEngine}(D-127)을 감싸 상태
 * I/O·시계·뷰·매치 기록 발행을 붙인다 — 스컬킹과 같은 2계층.
 *
 * <p><b>시계는 여기에만 있다.</b> 순수 엔진은 경쟁 창을 여는 순간의 시각을 인자로 받고 창이 저절로 닫히는
 * 시각을 계산할 뿐이다. 어댑터가 {@link Clock} 으로 그 시각을 넣고, 포트의 엔진 타이머(D-128)에는 "지금부터
 * 남은 시간"으로 바꿔 넘긴다.
 *
 * <p>1판 = 1매치 = 1라운드라 라운드가 끝나면 곧 매치가 끝난다. 본 클래스는 상태를 갖지 않는다(저장소 참조만)
 * — 동시성 직렬화는 호출자의 방 액션 락이 맡는다.
 */
public final class OneCardGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(OneCardGameEngine.class);

    private final GameContext context;
    private final OneCardEngine rules;
    private final OneCardStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public OneCardGameEngine(GameContext context,
                             OneCardStateStore stateStore,
                             Clock clock,
                             Random random,
                             RaceSettings raceSettings,
                             ApplicationEventPublisher publisher) {
        this.context = context;
        this.rules = new OneCardEngine(context, random, raceSettings);
        this.stateStore = stateStore;
        this.clock = clock;
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
        stateStore.save(context.roomId(), ocState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return OneCardAction.class;
    }

    /** 사람·봇·타임아웃 공용. 지금 시각은 경쟁 창을 열 때만 쓰인다. */
    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        OneCardEngine.Result result = rules.apply(ocState(state), seat, ocAction(action), clock.millis());
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return ocState(state).phaseName();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(ocState(state));
    }

    /** 1판 = 1라운드 — 라운드가 끝났다는 것은 매치가 끝났다는 것이다. */
    @Override
    public boolean isRoundOver(GameState state) {
        return ocState(state).ended();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(OneCardState::ended).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        return OneCardStateMapper.toTableView(ocState(state), clock.millis());
    }

    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.of(OneCardStateMapper.toPrivateView(ocState(state), seat));
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(ocState(state), seat));
    }

    /**
     * 휴리스틱 {@link OneCardBotPolicy} — 공개 정보 뷰({@link OneCardBotView})만 본다. 결정적이라 {@code random}
     * 은 쓰지 않는다. 경쟁 창의 누름은 여기서 하지 않는다(창을 열 때 추첨한 반응 시간으로 엔진 타이머가 맡는다).
     */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        return chooseBotAction(ocState(state), seat, OneCardBotPolicy::choose);
    }

    /**
     * 정책 호출 + 안전망. 둘 수 없으면(남의 차례·경쟁 창·끝난 판) null. 정책이 예외를 던지거나 null·합법수 밖
     * 액션을 내면 ERROR 로그 후 먹기({@code timeoutAction})로 떨어진다 — 턴 제한이 꺼진 방에서 정책 버그 하나로
     * 방이 멈추지 않게(스컬킹 D-119 와 같은 안전망).
     */
    GameAction chooseBotAction(OneCardState state, int seat,
                               BiFunction<OneCardBotView, List<OneCardAction>, OneCardAction> policy) {
        if (state.race() != null || !rules.pendingSeats(state).contains(seat)) {
            return null;
        }
        List<OneCardAction> legal = rules.legalActions(state, seat);
        try {
            OneCardAction chosen = policy.apply(OneCardBotView.of(state, seat), legal);
            if (chosen != null && legal.contains(chosen)) {
                return chosen;
            }
            log.error("OneCard bot policy returned a non-legal action, falling back: room={} seat={} action={}",
                    context.roomId(), seat, chosen);
        } catch (RuntimeException e) {
            log.error("OneCard bot policy failed, falling back: room={} seat={}", context.roomId(), seat, e);
        }
        return rules.timeoutAction(state, seat);
    }

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.timeoutAction(ocState(state), seat);
    }

    // ---------- 엔진 타이머 (D-128) ----------

    /** 경쟁 창이 저절로 닫히기까지 남은 시간 — 봇 누름 또는 창 끝. 이미 지났으면 0. */
    @Override
    public Optional<Duration> timer(GameState state) {
        var deadline = rules.timerDeadline(ocState(state));
        if (deadline.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.max(0L, deadline.getAsLong() - clock.millis())));
    }

    @Override
    public Optional<Result> onTimer(GameState state) {
        return rules.onTimer(ocState(state))
                .map(result -> new Result(result.newState(), List.<GameEvent>copyOf(result.events())));
    }

    // ---------- 매치 진행 ----------

    /**
     * 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED} 가 있으면) 기록 이벤트를 한 번 발행한다.
     * 이미 끝난 상태로 다시 불려도 이벤트가 없으므로 두 번 기록하지 않는다.
     */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        OneCardState state = ocState(newState);
        boolean endedNow = state.ended() && outbound.stream().anyMatch(OneCardEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state.result());
        return new Advance(true, true);
    }

    /**
     * 탈주 (§10) — 파산과 같은 탈락 경로. 순수 엔진의 3치 결과를 포트 3치로 옮긴다. 이미 끝났거나 이미 탈락한
     * 좌석이면 상태 무변경으로 {@code NOT_APPLICABLE}.
     */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        OneCardState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        OneCardEngine.Desertion desertion = rules.desert(state, seat);
        switch (desertion.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case CONTINUED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                log.warn("OneCard desertion continued: room={} seat={} userId={}",
                        context.roomId(), seat, deserterUserId);
                return DesertOutcome.MATCH_CONTINUES;
            }
            case MATCH_ENDED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                record(desertion.newState().result());
                log.warn("OneCard desertion ended match: room={} seat={} userId={} reason={}",
                        context.roomId(), seat, deserterUserId, desertion.newState().result().reason());
                return DesertOutcome.MATCH_ENDED;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /** 로컬 발행 — 기록기({@code OneCardMatchRecorder})가 듣는다. */
    private void record(MatchResult result) {
        publisher.publishEvent(new OneCardMatchCompleted(context.roomId(), context.playerIds(), result));
        log.info("OneCard match ended: room={} reason={} winners={}",
                context.roomId(), result.reason(), result.winners());
    }

    private static OneCardState ocState(GameState state) {
        if (state instanceof OneCardState oc) {
            return oc;
        }
        throw new IllegalArgumentException("Not a OneCardState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static OneCardAction ocAction(GameAction action) {
        if (action instanceof OneCardAction oc) {
            return oc;
        }
        throw new IllegalArgumentException("Not a OneCardAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
