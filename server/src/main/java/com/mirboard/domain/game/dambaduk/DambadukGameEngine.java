package com.mirboard.domain.game.dambaduk;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.bot.DambadukBotPolicy;
import com.mirboard.domain.game.dambaduk.bot.DambadukBotView;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.event.DambadukMatchCompleted;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.Phase;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 담바둑의 {@link GameEngine} 포트 어댑터 (D-278). 방 하나에 대응하는 per-room 인스턴스로 {@link DambadukGameDefinition#newEngine}
 * 이 만든다. 순수 룰 엔진 {@link DambadukEngine} 을 감싸 상태 I/O·뷰·봇·매치 기록 발행을 붙인다 — 오목과 같은 2계층.
 *
 * <p>포트에 주는 것: 비공개 뷰 없음(숨길 정보 없음), 엔진 타이머 없음(한 차례 = 한 액션이라 인프라 턴 데드라인이 곧 차례 제한), 미리보기
 * 없음. 탈주는 2인이면 매치 종료, 3인이면 돌을 치우고 계속(1명 남으면 종료). 1판 = 1매치 = 1라운드. 상태를 갖지 않는다 — 직렬화는 호출자의
 * 방 액션 락이 맡는다.
 */
public final class DambadukGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(DambadukGameEngine.class);

    private final GameContext context;
    private final DambadukEngine rules = new DambadukEngine();
    private final DambadukSettings settings;
    private final DambadukStateStore stateStore;
    private final ApplicationEventPublisher publisher;

    public DambadukGameEngine(GameContext context, DambadukStateStore stateStore, ApplicationEventPublisher publisher) {
        this.context = context;
        this.settings = DambadukSettings.from(context.options());
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
        stateStore.save(context.roomId(), dambaduk(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return DambadukAction.class;
    }

    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        DambadukEngine.Result r = rules.apply(dambaduk(state), seat, dambadukAction(action));
        return new Result(r.newState(), List.<GameEvent>copyOf(r.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return dambaduk(state).phase().name();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(dambaduk(state));
    }

    @Override
    public boolean isRoundOver(GameState state) {
        return dambaduk(state).isOver();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(DambadukState::isOver).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        return rules.publicView(dambaduk(state));
    }

    /** 숨길 정보가 없다(오목 D-175 와 같다). */
    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.empty();
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(dambaduk(state), seat));
    }

    /** 방 설정 {@code BOT_LEVEL} 의 정책(D-277). 결정적이라 {@code random} 은 쓰지 않는다. */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        return chooseBotAction(dambaduk(state), seat,
                (view, legal) -> DambadukBotPolicy.choose(view, legal, settings.botLevel()));
    }

    /**
     * 정책 호출 + 안전망. 둘 수 없으면(남의 차례·끝난 판) null. 정책이 예외·null·합법 수 밖 액션을 내면 ERROR 로그 후 시간 초과 자동 수와
     * 같은 수를 <b>보통 액션</b>({@code Place}·{@code Move})으로 둔다 — {@code TimeoutAct} 로 떨어지면 봇의 연속 시간 초과 수가 올라 정책
     * 버그 두 번에 봇이 빠진다(오목과 같은 이유).
     */
    GameAction chooseBotAction(DambadukState state, int seat,
                               BiFunction<DambadukBotView, List<DambadukAction>, DambadukAction> policy) {
        if (state.isOver() || seat != state.turnSeat()) {
            return null;
        }
        List<DambadukAction> legal = rules.legalActions(state, seat);
        if (legal.isEmpty()) {
            return null;
        }
        try {
            DambadukAction chosen = policy.apply(DambadukBotView.of(state, seat), legal);
            if (chosen != null && legal.contains(chosen)) {
                return chosen;
            }
            log.error("Dambaduk bot policy returned a non-legal action, falling back: room={} seat={} action={}",
                    context.roomId(), seat, chosen);
        } catch (RuntimeException e) {
            log.error("Dambaduk bot policy failed, falling back: room={} seat={}", context.roomId(), seat, e);
        }
        if (state.phase() == Phase.PLACING) {
            Cell c = rules.timeoutCell(state);
            return new DambadukAction.Place(rules.placeKinds(state, seat).getFirst(), c.x(), c.y());
        }
        return rules.timeoutMove(state, seat);
    }

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.timeoutAction(dambaduk(state), seat);
    }

    // ---------- 매치 진행 ----------

    /** 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED}) 기록 이벤트를 한 번 발행한다. */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        DambadukState state = dambaduk(newState);
        boolean endedNow = state.isOver() && outbound.stream().anyMatch(DambadukEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state);
        return new Advance(true, true);
    }

    /** 탈주 (룰 §9) — 2인은 그 사람 패로 끝, 3인은 돌을 치우고 계속(1명 남으면 끝). 해당 없으면 상태 무변경. */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        DambadukState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        DambadukEngine.Desertion d = rules.desert(state, seat);
        if (d.outcome() == DambadukEngine.Desertion.Outcome.NOT_APPLICABLE) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        stateStore.save(context.roomId(), d.newState());
        outbound.addAll(d.events());
        if (d.outcome() == DambadukEngine.Desertion.Outcome.MATCH_ENDED) {
            record(d.newState());
            log.warn("Dambaduk desertion ended match: room={} seat={} userId={}", context.roomId(), seat, deserterUserId);
            return DesertOutcome.MATCH_ENDED;
        }
        log.warn("Dambaduk desertion, match continues: room={} seat={} userId={}", context.roomId(), seat,
                deserterUserId);
        return DesertOutcome.MATCH_CONTINUES;
    }

    // ---------- internals ----------

    /**
     * 로컬 발행 — 기록기({@code DambadukMatchRecorder})가 듣는다. 리스너가 하나라 발행 지점에서 잡는다(매치 기록기 계약 3): 기록이
     * 실패해도 마지막 방송·대기실 복귀·턴 데드라인 취소가 이어진다. 결과를 실은 ERROR 로 남겨 수동 복구할 수 있게 한다.
     */
    private void record(DambadukState ended) {
        try {
            publisher.publishEvent(new DambadukMatchCompleted(context.roomId(), context.playerIds(), ended));
        } catch (RuntimeException e) {
            log.error("Dambaduk match record failed, the match still ends: room={} players={} result={}",
                    context.roomId(), context.playerIds(), ended.result(), e);
        }
        log.info("Dambaduk match ended: room={} seats={} reason={} winners={} moves={}",
                context.roomId(), ended.seatCount(), ended.result().reason(), ended.result().winners(),
                ended.moveCount());
    }

    private static DambadukState dambaduk(GameState state) {
        if (state instanceof DambadukState s) {
            return s;
        }
        throw new IllegalArgumentException("Not a DambadukState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static DambadukAction dambadukAction(GameAction action) {
        if (action instanceof DambadukAction a) {
            return a;
        }
        throw new IllegalArgumentException("Not a DambadukAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
