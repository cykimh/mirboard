package com.mirboard.domain.game.artillery;

import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.bot.ArtilleryBotPolicy;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.event.ArtilleryMatchCompleted;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.state.ArtilleryPublicView;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 씨앗포의 {@link GameEngine} 포트 어댑터 (S2, D-222). 방 하나에 대응하는 per-room 인스턴스로 {@link ArtilleryGameDefinition#newEngine}
 * 이 만든다. 순수 룰 엔진 {@link ArtilleryEngine}(S1)을 감싸 상태 I/O·시계·뷰·봇·매치 기록 발행을 붙인다(D-98 2계층).
 *
 * <p><b>포트 변경이 없다</b>(D-220). 차례당 액션이 하나(이동은 {@code FIRE} 에 실음)라 인프라 턴 데드라인으로 충분하고
 * {@code GameContext.turnSeconds} 는 쓰지 않는다. 발사 뒤 연출 대기({@code RESOLVING})만 포트 ⑦ 엔진 타이머로 건다 — 그동안
 * {@code pendingSeats} 가 비어 봇 루프·턴 타이머가 끼어들지 않는다(원카드 경쟁 창과 같은 모양). 판 시작 고르기 단계(D-224)의 15초도
 * 같은 엔진 타이머다 — 인프라 턴 데드라인은 고를 때마다 다시 걸리기 때문이다. 비공개 뷰는 고르기 단계의 내 선택뿐이다.
 */
public final class ArtilleryGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(ArtilleryGameEngine.class);

    private final GameContext context;
    private final ArtilleryEngine rules;
    private final ArtilleryStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public ArtilleryGameEngine(GameContext context,
                               ArtilleryEngine rules,
                               ArtilleryStateStore stateStore,
                               Clock clock,
                               ApplicationEventPublisher publisher) {
        this.context = context;
        this.rules = rules;
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
        stateStore.save(context.roomId(), aState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return ArtilleryAction.class;
    }

    /** 사람·봇·타임아웃 공용. 한 발은 여기서 전부 해소된다(이동 → 탄도 → 폭발 → 낙하). */
    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        ArtilleryEngine.Result r = rules.apply(aState(state), seat, aAction(action), clock.millis());
        return new Result(r.newState(), List.<GameEvent>copyOf(r.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return aState(state).phaseName();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(aState(state));
    }

    /** 1판 = 1매치. */
    @Override
    public boolean isRoundOver(GameState state) {
        return aState(state).ended();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(ArtilleryState::ended).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        ArtilleryState s = aState(state);
        OptionalLong remaining = rules.remainingMillis(s, clock.millis());
        return ArtilleryPublicView.of(s, remaining.isPresent() ? remaining.getAsLong() : null);
    }

    /** 고르기 단계에서 내가 고른 종류만(남에게는 숨긴다, D-224). 그 밖에는 숨길 것이 없다. */
    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        ArtilleryState s = aState(state);
        if (s.phase() != Phase.PICKING || seat < 0 || seat >= s.seatCount() || s.unit(seat).kind() == null) {
            return Optional.empty();
        }
        return Optional.of(new ArtilleryPrivateView(s.unit(seat).kind()));
    }

    /** 비공개 뷰 — 고르기 단계의 내 선택. */
    public record ArtilleryPrivateView(UnitKind pickedKind) {
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(aState(state), seat));
    }

    /** "보통" 봇 {@link ArtilleryBotPolicy} — 결정적이라 {@code random} 은 쓰지 않는다. */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        return chooseBotAction(aState(state), seat, ArtilleryBotPolicy::choose);
    }

    /** 정책 호출 + 안전망. 차례가 아니면 null. 예외·null·범위 밖이면 ERROR 로그 후 넘김. */
    GameAction chooseBotAction(ArtilleryState state, int seat,
                               BiFunction<ArtilleryState, Integer, ArtilleryAction> policy) {
        if (!rules.pendingSeats(state).contains(seat)) {
            return null;
        }
        if (state.phase() == Phase.PICKING) {
            return new ArtilleryAction.Pick(ArtilleryBotPolicy.pick(state, seat));
        }
        try {
            ArtilleryAction chosen = policy.apply(state, seat);
            if (chosen instanceof ArtilleryAction.Skip
                    || (chosen instanceof ArtilleryAction.Fire f && ArtilleryEngine.validFire(state, seat, f))) {
                return chosen;
            }
            log.error("Artillery bot policy returned an invalid action, skipping: room={} seat={} action={}",
                    context.roomId(), seat, chosen);
        } catch (RuntimeException e) {
            log.error("Artillery bot policy failed, skipping: room={} seat={}", context.roomId(), seat, e);
        }
        return new ArtilleryAction.Skip();
    }

    /** 시간 초과 = 넘김(룰 §4-5). 고르기 단계면 새싹포(엔진 타이머가 보통 먼저 끝낸다 — 폴백). */
    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        if (aState(state).phase() == Phase.PICKING) {
            return new ArtilleryAction.Pick(UnitKind.SPROUT);
        }
        return new ArtilleryAction.Skip();
    }

    // ---------- 엔진 타이머 — 연출 대기 (룰 §9.2) ----------

    @Override
    public Optional<Duration> timer(GameState state) {
        OptionalLong remaining = rules.remainingMillis(aState(state), clock.millis());
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.max(0L, remaining.getAsLong())));
    }

    /** 대기 끝 → 다음 차례. 인프라가 락 안에서 만기를 다시 확인한 뒤 부른다. */
    @Override
    public Optional<Result> onTimer(GameState state) {
        ArtilleryEngine.Result r = rules.expire(aState(state), clock.millis());
        if (r == null) {
            return Optional.empty();
        }
        return Optional.of(new Result(r.newState(), List.<GameEvent>copyOf(r.events())));
    }

    // ---------- 매치 진행 ----------

    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        ArtilleryState state = aState(newState);
        boolean endedNow = state.ended() && outbound.stream().anyMatch(ArtilleryEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state.result());
        return new Advance(true, true);
    }

    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        ArtilleryState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        ArtilleryEngine.Desertion d = rules.desert(state, seat);
        switch (d.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case MATCH_CONTINUES -> {
                stateStore.save(context.roomId(), d.newState());
                outbound.addAll(d.events());
                log.warn("Artillery desertion continued: room={} seat={} userId={}", context.roomId(), seat, deserterUserId);
                return DesertOutcome.MATCH_CONTINUES;
            }
            case MATCH_ENDED -> {
                stateStore.save(context.roomId(), d.newState());
                outbound.addAll(d.events());
                record(d.newState().result());
                log.warn("Artillery desertion ended match: room={} seat={} userId={} reason={}",
                        context.roomId(), seat, deserterUserId, d.newState().result().reason());
                return DesertOutcome.MATCH_ENDED;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /** 로컬 발행 — 기록기 하나가 동기로 듣는다. 실패는 여기서 잡아 진행을 계속한다(`docs/game-port.md` "매치 기록기 계약" 3). */
    private void record(MatchResult result) {
        try {
            publisher.publishEvent(new ArtilleryMatchCompleted(context.roomId(), context.playerIds(), result));
        } catch (RuntimeException e) {
            log.error("Artillery match record failed, the match still ends: room={} players={} result={}",
                    context.roomId(), context.playerIds(), result, e);
        }
        log.info("Artillery match ended: room={} reason={} winners={}", context.roomId(), result.reason(), result.winners());
    }

    private static ArtilleryState aState(GameState state) {
        if (state instanceof ArtilleryState a) {
            return a;
        }
        throw new IllegalArgumentException("Not an ArtilleryState: " + (state == null ? "null" : state.getClass().getName()));
    }

    private static ArtilleryAction aAction(GameAction action) {
        if (action instanceof ArtilleryAction a) {
            return a;
        }
        throw new IllegalArgumentException("Not an ArtilleryAction: " + (action == null ? "null" : action.getClass().getName()));
    }
}
