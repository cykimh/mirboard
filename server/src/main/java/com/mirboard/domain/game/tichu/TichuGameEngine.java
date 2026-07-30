package com.mirboard.domain.game.tichu;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.bot.LegalActionEnumerator;
import com.mirboard.domain.game.tichu.bot.RandomBotPolicy;
import com.mirboard.domain.game.tichu.bot.TimeoutActionPolicy;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.scoring.MvpCalculator;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.scoring.SeatContribution;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TichuStateMapper;
import com.mirboard.domain.game.tichu.state.TrickState;
import com.mirboard.infra.messaging.DomainEventBus;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-98 — 티츄의 {@link GameEngine} 포트 어댑터. 방 하나에 대응하는 per-room 인스턴스로
 * {@link TichuGameDefinition#newEngine} 이 만든다.
 *
 * <p>순수 룰 엔진 {@link TichuEngine} 을 감싸고, 인프라가 게임을 모른 채 인게임을 돌릴 수
 * 있도록 나머지를 붙인다: 상태 I/O, 공개/비공개 뷰, 대기 좌석 계산, 봇/타임아웃 액션,
 * 라운드·매치 진행. 두 클래스로 나눈 이유는 룰 단위 테스트가 Redis 를 끌고 오지 않게 하려는
 * 것이다 (`TichuEngineRoundSimulationTest` 등은 여전히 `new TichuEngine(ctx)` 만 쓴다).
 *
 * <p>과거 이 책임들이 있던 곳: {@code MatchProgressService.onRoundEnd}(infra.ws) →
 * {@link #advance}, {@code DesertionService} 의 티츄 판단(infra.ws) → {@link #desert},
 * 스케줄러 두 곳에 중복돼 있던 pending seat switch → {@link #pendingSeats}.
 *
 * <p>본 클래스는 상태를 갖지 않는다(저장소 참조만) — 동시성 직렬화는 호출자의
 * {@code RoomActionLock} 이 담당한다.
 */
public final class TichuGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(TichuGameEngine.class);

    private final GameContext context;
    private final TichuEngine rules;
    private final TichuGameStateStore stateStore;
    private final TichuMatchStateStore matchStateStore;
    private final TichuRoundStarter roundStarter;
    private final DomainEventBus events;

    public TichuGameEngine(GameContext context,
                           TichuGameStateStore stateStore,
                           TichuMatchStateStore matchStateStore,
                           TichuRoundStarter roundStarter,
                           DomainEventBus events) {
        this.context = context;
        this.rules = new TichuEngine(context);
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
        this.roundStarter = roundStarter;
        this.events = events;
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
        stateStore.save(context.roomId(), tichuState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return TichuAction.class;
    }

    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        TichuEngine.Result result = rules.apply(tichuState(state), seat, tichuAction(action));
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return TichuStateMapper.phaseName(tichuState(state));
    }

    /**
     * Dealing/Passing 은 <b>여러 좌석이 동시에</b> 선언/제출을 기다리므로 복수를 돌려준다.
     * Playing 은 항상 한 좌석(용 트릭 양도 보류 중이면 트릭을 가져간 좌석).
     */
    @Override
    public List<Integer> pendingSeats(GameState state) {
        return switch (tichuState(state)) {
            case TichuState.Dealing d -> seatsWhere(d.players().size(), s -> !d.ready().contains(s));
            case TichuState.Passing p ->
                    seatsWhere(p.players().size(), s -> !p.submitted().containsKey(s));
            case TichuState.Playing pl -> playingPendingSeats(pl);
            case TichuState.RoundEnd __ -> List.of();
        };
    }

    @Override
    public boolean isRoundOver(GameState state) {
        return tichuState(state) instanceof TichuState.RoundEnd;
    }

    @Override
    public boolean isMatchOver() {
        return matchStateStore.load(context.roomId())
                .map(TichuMatchState::isMatchOver)
                .orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        TichuMatchState matchState = matchState();
        return TichuStateMapper.toTableView(
                tichuState(state), matchState.scoresByTeam(), matchState.roundNumber());
    }

    /** 티츄는 손패가 비공개이므로 항상 존재한다 (요트 같은 게임은 empty 를 돌려준다). */
    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.of(TichuStateMapper.toPrivateHand(tichuState(state), seat));
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(LegalActionEnumerator.enumerate(tichuState(state), seat));
    }

    /**
     * {@link RandomBotPolicy} 에 위임 — 포트 기본값(합법 액션 균등 분포)과 결과는 같지만
     * 정책이 게임 쪽에 남아 있어야 휴리스틱을 넣을 자리가 생긴다. 시드 재현성은 호출자가
     * 넘긴 {@code random} 인스턴스가 유지한다.
     */
    @Override
    public GameAction botAction(GameState state, int seat, Random random) {
        return new RandomBotPolicy(random).choose(tichuState(state), seat);
    }

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return TimeoutActionPolicy.choose(tichuState(state), seat);
    }

    // ---------- 라운드 · 매치 진행 ----------

    /**
     * RoundEnd 도달 시 점수 누적 + 매치 종료 / 다음 라운드 시작 분기. 발행할 이벤트
     * (MatchEnded 또는 RoundStarted) 를 {@code outbound} 에 in-place 추가한다 — 호출자가
     * 액션 이벤트들과 한 번에 브로드캐스트하도록.
     */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        if (!(tichuState(newState) instanceof TichuState.RoundEnd ended)) {
            return Advance.NONE;
        }
        RoundScore lastScore = roundScoreOf(ended, outbound);
        List<SeatContribution> roundContribs = MvpCalculator.roundContributions(ended.players());
        TichuMatchState afterRound = matchState().withRoundCompleted(lastScore, roundContribs);
        matchStateStore.save(context.roomId(), afterRound);

        log.info("Round completed: round={} A={} B={} cumulativeA={} cumulativeB={}",
                afterRound.roundNumber() - 1, lastScore.teamAScore(), lastScore.teamBScore(),
                afterRound.cumulativeA(), afterRound.cumulativeB());

        if (!afterRound.isMatchOver()) {
            // 매치 계속 — 다음 라운드 Dealing(8) 생성 + RoundStarted 알림.
            roundStarter.startRound(context.roomId(), context.playerIds(), afterRound.roundNumber());
            outbound.add(new TichuEvent.RoundStarted(
                    afterRound.roundNumber(), afterRound.scoresByTeam()));
            return new Advance(true, false);
        }

        Team winner = afterRound.winningTeam();
        Optional<MvpCalculator.Mvp> mvp = MvpCalculator.select(
                afterRound.contributions(), context.playerIds(), winner, context.botSeats());
        outbound.add(new TichuEvent.MatchEnded(
                winner,
                afterRound.scoresByTeam(),
                afterRound.roundScores().size(),
                mvp.map(MvpCalculator.Mvp::userId).orElse(null),
                mvp.map(MvpCalculator.Mvp::stat).orElse(null)));
        events.publish(new TichuMatchCompleted(
                context.roomId(),
                context.playerIds(),
                afterRound.cumulativeA(),
                afterRound.cumulativeB(),
                winner,
                afterRound.roundScores(),
                null,               // 정상 종료 — 탈주자 없음 (Phase 19, D-75).
                context.stake()));  // D-81 — 칩 정산용 판돈.
        log.info("Match ended: winner={} rounds={} A={} B={}",
                winner, afterRound.roundScores().size(),
                afterRound.cumulativeA(), afterRound.cumulativeB());
        return new Advance(true, true);
    }

    /**
     * 탈주 처리 (Phase 19/D-75). 티츄는 2:2 고정이라 한 명이 빠지면 속행이 불가능하므로
     * <b>상대팀 승리</b>로 매치를 즉시 종료한다. 누적 점수는 보존하고 승팀만 강제하며,
     * 기존 {@link TichuMatchCompleted} 경로를 재사용해 {@code MatchResultRecorder} 가
     * win/lose/ELO + 탈주자 desert_count 를 한 트랜잭션에 기록하게 한다.
     */
    @Override
    public boolean desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        TichuMatchState matchState = matchState();
        // D-82 — 매치가 이미 끝난(리매치 대기) 방의 끊김/나가기는 탈주가 아니다.
        if (matchState.isMatchOver()) {
            return false;
        }
        Team winner = Team.ofSeat(seat).opponent();
        events.publish(new TichuMatchCompleted(
                context.roomId(),
                context.playerIds(),
                matchState.cumulativeA(),
                matchState.cumulativeB(),
                winner,
                matchState.roundScores(),
                deserterUserId,
                context.stake()));  // D-81 — 탈주 패배 정산용 판돈.
        outbound.add(new TichuEvent.MatchEnded(
                winner,
                matchState.scoresByTeam(),
                matchState.roundScores().size(),
                null,
                null));
        log.warn("Desertion: roomId={} seat={} deserterUserId={} winner={}",
                context.roomId(), seat, deserterUserId, winner);
        return true;
    }

    // ---------- internals ----------

    /** 영속된 매치 상태. 없으면(첫 라운드 진행 중) 방 설정으로 초기 상태를 만든다. */
    private TichuMatchState matchState() {
        return matchStateStore.load(context.roomId())
                .orElseGet(() -> TichuMatchState.initial(
                        context.playerIds(), context.targetScore()));
    }

    /**
     * 엔진이 이미 발행한 RoundEnded 이벤트에서 라운드 점수를 꺼낸다 (선언 성패/완주 순서가
     * 반영된 정본). 없으면 RoundEnd 상태의 팀 점수로 폴백.
     */
    private static RoundScore roundScoreOf(TichuState.RoundEnd ended, List<GameEvent> outbound) {
        return outbound.stream()
                .filter(TichuEvent.RoundEnded.class::isInstance)
                .map(TichuEvent.RoundEnded.class::cast)
                .map(TichuEvent.RoundEnded::score)
                .findFirst()
                .orElseGet(() -> new RoundScore(
                        ended.teamAScore(), ended.teamBScore(), -1, false));
    }

    private static List<Integer> playingPendingSeats(TichuState.Playing playing) {
        TrickState trick = playing.trick();
        // 용으로 트릭을 가져간 좌석은 양도(GiveDragonTrick)를 마칠 때까지 차례를 붙잡는다.
        if (dragonGivePending(trick)) {
            return List.of(trick.currentTopSeat());
        }
        int current = trick.currentTurnSeat();
        if (current < 0 || playing.players().get(current).isFinished()) {
            return List.of();
        }
        return List.of(current);
    }

    private static boolean dragonGivePending(TrickState trick) {
        return trick.currentTop() != null
                && trick.currentTop().cards().size() == 1
                && trick.currentTop().cards().get(0).is(Special.DRAGON);
    }

    private static List<Integer> seatsWhere(int seatCount, java.util.function.IntPredicate pending) {
        return IntStream.range(0, seatCount).filter(pending).boxed().toList();
    }

    private static TichuState tichuState(GameState state) {
        if (state instanceof TichuState tichu) {
            return tichu;
        }
        throw new IllegalArgumentException("Not a TichuState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static TichuAction tichuAction(GameAction action) {
        if (action instanceof TichuAction tichu) {
            return tichu;
        }
        throw new IllegalArgumentException("Not a TichuAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
