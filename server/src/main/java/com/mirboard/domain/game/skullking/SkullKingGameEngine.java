package com.mirboard.domain.game.skullking;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.skullking.action.RejectionReason;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.action.SkullKingActionRejectedException;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.SkullKingStateMapper;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-102 — 스컬킹의 {@link GameEngine} 포트 어댑터. 방 하나에 대응하는 per-room 인스턴스로
 * {@link SkullKingGameDefinition#newEngine} 이 만든다. 순수 룰 엔진 {@link SkullKingEngine}
 * 을 감싸 상태 I/O·뷰·라운드/매치 진행·탈주를 붙인다 (티츄 {@code TichuGameEngine} 와
 * 같은 2계층, D-98/D-101).
 *
 * <p><b>드레인 계약(D-104)이 이 어댑터 안에서 닫힌다.</b> 유령(탈주) 좌석이 있는 방의
 * 액션은 순수 엔진의 {@code applyAndDrain} 을 타고, 라운드 시작은
 * {@code startRoundAndDrain} 을 탄다 — 인프라(컨트롤러·스케줄러)는 드레인의 존재를
 * 모른다.
 *
 * <p>본 클래스는 상태를 갖지 않는다(저장소 참조만) — 동시성 직렬화는 호출자의
 * {@code RoomActionLock} 이 담당한다.
 */
public final class SkullKingGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(SkullKingGameEngine.class);

    private final GameContext context;
    private final SkullKingEngine rules;
    private final SkullKingStateStore stateStore;
    private final SkullKingMatchStateStore matchStateStore;
    private final SecureRandom random;

    public SkullKingGameEngine(GameContext context,
                               SkullKingStateStore stateStore,
                               SkullKingMatchStateStore matchStateStore,
                               SecureRandom random) {
        this.context = context;
        this.rules = new SkullKingEngine(context);
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
        this.random = random;
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
        stateStore.save(context.roomId(), skState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return SkullKingAction.class;
    }

    /**
     * 사람/봇 공용 적용 경로. 탈주 좌석의 액션은 {@code SEAT_DESERTED} 로 거절되고,
     * 적용 직후 유령 차례가 자동 소화된다({@code applyAndDrain}) — 호출자는 결과 이벤트를
     * 그대로 브로드캐스트만 하면 된다.
     */
    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        SkullKingEngine.Result result = rules.applyAndDrain(
                skState(state), matchState(), seat, skAction(action));
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return skState(state).phaseName();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(skState(state));
    }

    @Override
    public boolean isRoundOver(GameState state) {
        return rules.isRoundOver(skState(state));
    }

    @Override
    public boolean isMatchOver() {
        return matchStateStore.load(context.roomId())
                .map(SkullKingMatchState::isMatchOver)
                .orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        SkullKingMatchState match = matchState();
        return SkullKingStateMapper.toTableView(
                skState(state), match.cumulativeScores(), match.desertedSeats());
    }

    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.of(SkullKingStateMapper.toPrivateView(skState(state), seat));
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(skState(state), seat));
    }

    // botAction 은 포트 기본값(합법 액션 균등 분포)을 그대로 쓴다 (D-102 보류 ②).

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.timeoutAction(skState(state), seat);
    }

    // ---------- 라운드 · 매치 진행 ----------

    /**
     * RoundEnd 도달 시: 점수를 매치에 누적하고 매치 종료 / 다음 라운드 시작으로 분기한다.
     * 다음 라운드는 {@code startRoundAndDrain} 으로 시작해 상태를 직접 영속화하고, 발행할
     * 이벤트(RoundEnded·MatchEnded 또는 새 라운드의 BiddingStarted/HandDealt)를
     * {@code outbound} 에 append 한다.
     */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        if (!(skState(newState) instanceof SkullKingState.RoundEnd ended)) {
            return Advance.NONE;
        }
        SkullKingEngine.Settlement settled = rules.settleRound(ended, matchState());
        SkullKingMatchState afterRound = settled.matchState();
        matchStateStore.save(context.roomId(), afterRound);
        outbound.addAll(settled.events());

        log.info("SkullKing round completed: room={} round={} cumulative={}",
                context.roomId(), ended.roundNumber(), afterRound.cumulativeScores());

        if (afterRound.isMatchOver()) {
            log.info("SkullKing match ended: room={} winners={} scores={}",
                    context.roomId(), afterRound.winners(), afterRound.cumulativeScores());
            return new Advance(true, true);
        }

        SkullKingEngine.Result nextRound = rules.startRoundAndDrain(afterRound, random);
        stateStore.save(context.roomId(), nextRound.newState());
        outbound.addAll(nextRound.events());
        return new Advance(true, false);
    }

    /**
     * 탈주 (D-104) — "남은 사람끼리 계속". 순수 엔진의 3치 결과를 포트 3치로 그대로
     * 매핑한다. CONTINUED 인데 드레인이 라운드를 끝냈으면 여기서 정산·다음 라운드까지
     * 이어 돌린다(사람 차례 또는 매치 종료까지) — 인프라는 그 사이 상태를 보지 않는다.
     */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        SkullKingState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        SkullKingEngine.Desertion desertion =
                rules.desert(state, matchState(), seat, humanSeats());
        switch (desertion.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case MATCH_ENDED -> {
                matchStateStore.save(context.roomId(), desertion.matchState());
                outbound.addAll(desertion.events());
                log.warn("SkullKing desertion ended match: room={} seat={} userId={} winners={}",
                        context.roomId(), seat, deserterUserId, desertion.matchState().winners());
                return DesertOutcome.MATCH_ENDED;
            }
            case CONTINUED -> {
                matchStateStore.save(context.roomId(), desertion.matchState());
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                log.warn("SkullKing desertion continued: room={} seat={} userId={} deserted={}",
                        context.roomId(), seat, deserterUserId,
                        desertion.matchState().desertedSeats());
                // 드레인이 라운드를 끝냈을 수 있다 — 정산과 다음 라운드 시작까지 마저 민다.
                Advance advanced = advance(desertion.newState(), outbound);
                return advanced.matchCompleted()
                        ? DesertOutcome.MATCH_ENDED
                        : DesertOutcome.MATCH_CONTINUES;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /** 영속된 매치 상태. 없으면(방금 시작) 좌석 0 시작의 초기 상태 — 리스너가 곧 채운다. */
    private SkullKingMatchState matchState() {
        return matchStateStore.load(context.roomId())
                .orElseGet(() -> SkullKingMatchState.initial(context.seatCount(), 0));
    }

    /** 사람이 점유한 좌석 = 전체 − 봇 (D-104 조기 종료 판정용). */
    private Set<Integer> humanSeats() {
        Set<Integer> humans = new HashSet<>();
        for (int seat = 0; seat < context.seatCount(); seat++) {
            humans.add(seat);
        }
        context.botSeats().forEach(humans::remove);
        return humans;
    }

    private static SkullKingState skState(GameState state) {
        if (state instanceof SkullKingState sk) {
            return sk;
        }
        throw new IllegalArgumentException("Not a SkullKingState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static SkullKingAction skAction(GameAction action) {
        if (action instanceof SkullKingAction sk) {
            return sk;
        }
        throw new IllegalArgumentException("Not a SkullKingAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
