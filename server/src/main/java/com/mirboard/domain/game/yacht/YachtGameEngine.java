package com.mirboard.domain.game.yacht;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.bot.YachtBotPolicy;
import com.mirboard.domain.game.yacht.bot.YachtBotView;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.event.YachtMatchCompleted;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.YachtState;
import com.mirboard.domain.game.yacht.state.YachtStateMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 요트의 {@link GameEngine} 포트 어댑터 (S3). 방 하나에 대응하는 per-room 인스턴스로 {@link YachtGameDefinition#newEngine}
 * 이 만든다. 순수 룰 엔진 {@link YachtEngine}(S2)을 감싸 상태 I/O·시계·난수·뷰·매치 기록 발행을 붙인다(D-98 2계층).
 *
 * <p><b>차례 전체 제한(D-183)</b>은 포트 ⑦ 엔진 타이머로 건다 — 인프라 턴 타이머는 액션마다 처음부터 다시 걸려 홀드를 바꾸기만
 * 해도 늘어나기 때문이다. {@link #timer} 는 차례 시작 시각 + {@code GameContext.turnSeconds} 까지 남은 시간이고, 만기면
 * {@link #onTimer} 가 자동 기록한다. 인프라 턴 데드라인(마지막 액션 + 같은 시간)은 항상 더 늦어 {@link #timeoutAction} 은
 * 폴백으로만 남는다.
 *
 * <p>D-268: 컵 흔들기는 포트 ⑧ {@link #preview} 로만 — 상태를 바꾸지 않는다(씨앗포 조준 미리보기에 이은 두 번째 구현).
 *
 * <p>비공개 뷰가 없다({@link #privateView} = empty). 숨길 정보가 없다(`docs/rules-yacht.md` §9.2).
 */
public final class YachtGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(YachtGameEngine.class);

    private final GameContext context;
    private final YachtEngine rules;
    private final YachtStateStore stateStore;
    private final Clock clock;
    private final DiceRoller roller;
    private final ApplicationEventPublisher publisher;

    public YachtGameEngine(GameContext context,
                           YachtStateStore stateStore,
                           Clock clock,
                           Random random,
                           ApplicationEventPublisher publisher) {
        this.context = context;
        this.rules = new YachtEngine(context);
        this.stateStore = stateStore;
        this.clock = clock;
        this.roller = DiceRoller.from(random);
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
        stateStore.save(context.roomId(), yState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return YachtAction.class;
    }

    /** 사람·봇·타임아웃 공용. 주사위는 굴리는 순간 여기서 정한다(상태에 미래 눈 없음 — D-182). */
    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        YachtEngine.Result result = rules.apply(yState(state), seat, yAction(action), roller, clock.millis());
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    // ---------- 컵 흔들기 미리보기 (D-268) ----------

    /** 포트 ⑧ — 흔들기 입력만. 굴릴 수 있는 좌석인지는 순수 엔진이 본다. 그 밖의 입력(굴리기·고정·기록)은 없음. */
    @Override
    public Optional<GameEvent> preview(GameState state, int seat, GameAction input) {
        if (!(state instanceof YachtState s) || !(input instanceof YachtAction.Shake shake)) {
            return Optional.empty();
        }
        return rules.preview(s, seat, shake).map(GameEvent.class::cast);
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return yState(state).phaseName();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(yState(state));
    }

    /** 1판 = 1매치 — 라운드가 끝나면 매치가 끝난다(12라운드는 엔진 안의 숫자). */
    @Override
    public boolean isRoundOver(GameState state) {
        return yState(state).ended();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(YachtState::ended).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        YachtState s = yState(state);
        OptionalLong remaining = rules.remainingMillis(s, clock.millis());
        return YachtStateMapper.toPublicView(s, remaining.isPresent() ? remaining.getAsLong() : null);
    }

    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.empty();
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(yState(state), seat));
    }

    /** 휴리스틱 {@link YachtBotPolicy} — 공개 정보 뷰만 본다. 결정적이라 {@code random} 은 쓰지 않는다. */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        return chooseBotAction(yState(state), seat, YachtBotPolicy::choose);
    }

    /**
     * 정책 호출 + 안전망. 둘 수 없으면(남의 차례·끝난 판) null. 정책이 예외·null·합법수 밖 액션을 내면 ERROR 로그 후
     * 폴백 수({@link YachtEngine#fallbackAction})로 떨어진다 — 턴 제한이 꺼진 방에서 정책 버그 하나로 방이 멈추지 않게.
     */
    GameAction chooseBotAction(YachtState state, int seat, Function<YachtBotView, YachtAction> policy) {
        if (!rules.pendingSeats(state).contains(seat)) {
            return null;
        }
        List<YachtAction> legal = rules.legalActions(state, seat);
        try {
            YachtAction chosen = policy.apply(YachtBotView.of(state, seat));
            if (chosen != null && legal.contains(chosen)) {
                return rules.simultaneous() ? carryHoldIntoRoll(chosen) : chosen;
            }
            log.error("Yacht bot policy returned a non-legal action, falling back: room={} seat={} action={}",
                    context.roomId(), seat, chosen);
        } catch (RuntimeException e) {
            log.error("Yacht bot policy failed, falling back: room={} seat={}", context.roomId(), seat, e);
        }
        return rules.fallbackAction(state, seat);
    }

    /**
     * D-202 — 동시 진행에서는 봇의 홀드를 다음 굴림에 실어 한 액션으로 보낸다(봇 간격은 그대로라 차례당 액션이 줄면 사람이 덜 기다린다).
     * 홀드 뒤에는 정책이 늘 굴리므로 같은 결과다. 다 잡는 홀드는 굴릴 것이 없어 그대로 둔다(정책은 그런 홀드를 내지 않는다).
     */
    private static YachtAction carryHoldIntoRoll(YachtAction chosen) {
        if (chosen instanceof YachtAction.Hold hold && !hold.held().all()) {
            return new YachtAction.Roll(hold.held());
        }
        return chosen;
    }

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.fallbackAction(yState(state), seat);
    }

    // ---------- 엔진 타이머 — 차례 전체 제한 (D-183) ----------

    /** 차례 마감까지 남은 시간(이미 지났으면 0). 턴 제한이 꺼졌거나 끝난 판이면 없음. */
    @Override
    public Optional<Duration> timer(GameState state) {
        OptionalLong remaining = rules.remainingMillis(yState(state), clock.millis());
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.max(0L, remaining.getAsLong())));
    }

    /**
     * 만기 — (안 굴렸으면 한 번 굴리고) 점수가 가장 높은 빈 칸에 자동 기록(§6-2). 동시 진행이면 아직 안 한 사람 모두(D-202). 인프라가 락
     * 안에서 만기를 다시 확인한 뒤 부른다.
     */
    @Override
    public Optional<Result> onTimer(GameState state) {
        YachtState s = yState(state);
        if (s.ended()) {
            return Optional.empty();
        }
        YachtEngine.Result result = rules.expire(s, roller, clock.millis());
        log.info("Yacht turn expired: room={} seats={} round={}", context.roomId(), s.pendingSeats(), s.round());
        return Optional.of(new Result(result.newState(), List.<GameEvent>copyOf(result.events())));
    }

    // ---------- 매치 진행 ----------

    /** 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED} 가 있으면) 기록 이벤트를 한 번 발행한다. */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        YachtState state = yState(newState);
        boolean endedNow = state.ended() && outbound.stream().anyMatch(YachtEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state.result());
        return new Advance(true, true);
    }

    /** 탈주 (§7) — 순수 엔진의 3치 결과를 포트 3치로 옮긴다. */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        YachtState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        YachtEngine.Desertion desertion = rules.desert(state, seat, clock.millis());
        switch (desertion.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case MATCH_CONTINUES -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                log.warn("Yacht desertion continued: room={} seat={} userId={}", context.roomId(), seat, deserterUserId);
                return DesertOutcome.MATCH_CONTINUES;
            }
            case MATCH_ENDED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                record(desertion.newState().result());
                log.warn("Yacht desertion ended match: room={} seat={} userId={} reason={}",
                        context.roomId(), seat, deserterUserId, desertion.newState().result().reason());
                return DesertOutcome.MATCH_ENDED;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /**
     * 로컬 발행 — 기록기({@code YachtMatchRecorder})가 듣는다. 기록기는 동기 리스너 하나라 실패를 여기서 잡아 결과를 실은 ERROR 로
     * 남기고 진행은 계속한다(`docs/game-port.md` "매치 기록기 계약" 3, 원카드·스컬킹과 같다).
     */
    private void record(MatchResult result) {
        try {
            publisher.publishEvent(new YachtMatchCompleted(context.roomId(), context.playerIds(), result));
        } catch (RuntimeException e) {
            log.error("Yacht match record failed, the match still ends: room={} players={} result={}",
                    context.roomId(), context.playerIds(), result, e);
        }
        log.info("Yacht match ended: room={} reason={} winners={}", context.roomId(), result.reason(), result.winners());
    }

    private static YachtState yState(GameState state) {
        if (state instanceof YachtState y) {
            return y;
        }
        throw new IllegalArgumentException("Not a YachtState: " + (state == null ? "null" : state.getClass().getName()));
    }

    private static YachtAction yAction(GameAction action) {
        if (action instanceof YachtAction y) {
            return y;
        }
        throw new IllegalArgumentException("Not a YachtAction: " + (action == null ? "null" : action.getClass().getName()));
    }
}
