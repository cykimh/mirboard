package com.mirboard.domain.game.omok;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.bot.OmokBotPolicy;
import com.mirboard.domain.game.omok.bot.OmokBotView;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.event.OmokMatchCompleted;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 오목의 {@link GameEngine} 포트 어댑터 (D-175). 방 하나에 대응하는 per-room 인스턴스로 {@link OmokGameDefinition#newEngine}
 * 이 만든다. 순수 룰 엔진 {@link OmokEngine} 을 감싸 상태 I/O·뷰·봇·매치 기록 발행을 붙인다 — 원카드와 같은 2계층.
 *
 * <p>포트에 주는 것: 비공개 뷰 없음({@link #privateView} = empty — 숨길 정보가 없다), 엔진 타이머 없음(기본), 탈주는 늘 매치
 * 종료(1:1 남은 사람 승, 2:2 탈주자 팀 패). 1판 = 1매치 = 1라운드. 상태를 갖지 않는다 — 직렬화는 호출자의 방 액션 락이 맡는다.
 */
public final class OmokGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(OmokGameEngine.class);

    private final GameContext context;
    private final OmokEngine rules = new OmokEngine();
    private final OmokSettings settings;
    private final OmokStateStore stateStore;
    private final ApplicationEventPublisher publisher;

    public OmokGameEngine(GameContext context, OmokStateStore stateStore, ApplicationEventPublisher publisher) {
        this.context = context;
        this.settings = OmokSettings.from(context.options());
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
        stateStore.save(context.roomId(), omok(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return OmokAction.class;
    }

    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        OmokEngine.Result r = rules.apply(omok(state), seat, omokAction(action));
        return new Result(r.newState(), List.<GameEvent>copyOf(r.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return omok(state).isOver() ? "ENDED" : "PLAYING";
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(omok(state));
    }

    @Override
    public boolean isRoundOver(GameState state) {
        return omok(state).isOver();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(OmokState::isOver).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        return rules.publicView(omok(state));
    }

    /** 숨길 정보가 없다 — 포트 §1 설계 판단 2 의 "비공개 뷰 없음"을 처음 실제로 쓴다. */
    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.empty();
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(omok(state), seat));
    }

    /** 방 설정 {@code BOT_LEVEL} 의 정책(D-179). 결정적이라 {@code random} 은 쓰지 않는다. */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        return chooseBotAction(omok(state), seat,
                (view, legal) -> OmokBotPolicy.choose(view, legal, settings.botLevel()));
    }

    /**
     * 정책 호출 + 안전망. 둘 수 없으면(남의 차례·끝난 판) null. 정책이 예외·null·합법수 밖 액션을 내면 ERROR 로그 후 시간 초과
     * 자동 수와 같은 자리에 <b>보통 착수</b>({@code Place})로 둔다 — {@code timeoutAction}({@code TimeoutPlace})으로 떨어지면 봇의
     * 연속 시간 초과 수가 올라 정책 버그 두 번에 봇이 시간패한다.
     */
    GameAction chooseBotAction(OmokState state, int seat,
                               BiFunction<OmokBotView, List<OmokAction>, OmokAction> policy) {
        if (state.isOver() || seat != state.turnSeat()) {
            return null;
        }
        List<OmokAction> legal = rules.legalActions(state, seat);
        if (legal.isEmpty()) {
            return null;
        }
        try {
            OmokAction chosen = policy.apply(OmokBotView.of(state), legal);
            if (chosen != null && legal.contains(chosen)) {
                return chosen;
            }
            log.error("Omok bot policy returned a non-legal action, falling back: room={} seat={} action={}",
                    context.roomId(), seat, chosen);
        } catch (RuntimeException e) {
            log.error("Omok bot policy failed, falling back: room={} seat={}", context.roomId(), seat, e);
        }
        Point p = rules.timeoutPoint(state);
        return new OmokAction.Place(p.x(), p.y());
    }

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.timeoutAction(omok(state), seat);
    }

    // ---------- 매치 진행 ----------

    /** 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED}) 기록 이벤트를 한 번 발행한다. */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        OmokState state = omok(newState);
        boolean endedNow = state.isOver() && outbound.stream().anyMatch(OmokEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state);
        return new Advance(true, true);
    }

    /** 탈주 (룰 §9) — 그 편 패로 판이 끝난다. 이미 끝났으면 상태 무변경으로 {@code NOT_APPLICABLE}. */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        OmokState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        OmokEngine.Desertion d = rules.desert(state, seat);
        if (!d.ended()) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        stateStore.save(context.roomId(), d.newState());
        outbound.addAll(d.events());
        record(d.newState());
        log.warn("Omok desertion ended match: room={} seat={} userId={}", context.roomId(), seat, deserterUserId);
        return DesertOutcome.MATCH_ENDED;
    }

    // ---------- internals ----------

    /**
     * 로컬 발행 — 기록기({@code OmokMatchRecorder})가 듣는다. 리스너가 하나라 발행 지점에서 잡는다(매치 기록기 계약 3): 기록이
     * 실패해도 마지막 방송·대기실 복귀·턴 데드라인 취소가 이어진다. 결과를 실은 ERROR 로 남겨 수동 복구할 수 있게 한다.
     */
    private void record(OmokState ended) {
        try {
            publisher.publishEvent(new OmokMatchCompleted(context.roomId(), context.playerIds(), ended));
        } catch (RuntimeException e) {
            log.error("Omok match record failed, the match still ends: room={} players={} result={}",
                    context.roomId(), context.playerIds(), ended.result(), e);
        }
        log.info("Omok match ended: room={} mode={} rule={} reason={} winners={} moves={}",
                context.roomId(), ended.mode(), ended.rule(), ended.result().reason(), ended.result().winnerSeats(),
                ended.moves().size());
    }

    private static OmokState omok(GameState state) {
        if (state instanceof OmokState s) {
            return s;
        }
        throw new IllegalArgumentException("Not an OmokState: " + (state == null ? "null" : state.getClass().getName()));
    }

    private static OmokAction omokAction(GameAction action) {
        if (action instanceof OmokAction a) {
            return a;
        }
        throw new IllegalArgumentException("Not an OmokAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
