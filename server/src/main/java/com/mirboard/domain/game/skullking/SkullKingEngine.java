package com.mirboard.domain.game.skullking;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.action.ActionValidator;
import com.mirboard.domain.game.skullking.action.RejectionReason;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.action.SkullKingActionRejectedException;
import com.mirboard.domain.game.skullking.bid.BidRules;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.event.SkullKingEvent;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.scoring.RoundScorer;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import com.mirboard.domain.game.skullking.state.TrickState;
import com.mirboard.domain.game.skullking.trick.TrickResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 스컬킹 <b>순수 룰 엔진</b> (`docs/rules-skullking.md`).
 *
 * <p>Spring·Redis 를 모른다. 포트 어댑터({@code SkullKingGameEngine implements GameEngine})와
 * {@code GameDefinition} 등록·상태 저장·뷰 매퍼·봇 정책은 <b>S5(D-102)</b> 범위다 —
 * 티츄의 순수 {@code TichuEngine} + 어댑터 {@code TichuGameEngine} 2층 구조와 같은 선이며,
 * {@code GameEngine} javadoc 이 권장하는 구조다. 덕분에 본 클래스의 테스트는 Docker 없이
 * 돈다.
 *
 * <p>라운드 라이프사이클 (§3): {@link #startRound} → {@code Bidding} → 전원 입찰 →
 * {@code Playing}(트릭 반복) → 손패 소진 → {@code RoundEnd} → {@link #settleRound}.
 */
public final class SkullKingEngine {

    private final GameContext context;

    public SkullKingEngine(GameContext context) {
        this.context = context;
    }

    public GameContext context() {
        return context;
    }

    /** 액션 적용 결과 — 새 상태 + 발행할 이벤트. */
    public record Result(SkullKingState newState, List<SkullKingEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    /** 라운드 정산 결과 — 누적된 매치 상태 + 발행할 이벤트. */
    public record Settlement(SkullKingMatchState matchState, List<SkullKingEvent> events) {
        public Settlement {
            events = List.copyOf(events);
        }
    }

    /** {@link #desert} 결과 — 라운드/매치 상태 + 무엇이 일어났는가 + 발행할 이벤트. */
    public record Desertion(SkullKingState newState,
                            SkullKingMatchState matchState,
                            Outcome outcome,
                            List<SkullKingEvent> events) {

        public enum Outcome {
            /** 가드에 걸림(멱등) — 상태 무변경, 이벤트 0건. */
            NOT_APPLICABLE,
            /** 남은 사람끼리 계속 — 유령 차례는 드레인 완료 (§13-⑱). */
            CONTINUED,
            /** 잔존 좌석/사람 부족으로 조기 종료 — 진행 라운드 폐기 (§13-⑲). */
            MATCH_ENDED
        }

        public Desertion {
            events = List.copyOf(events);
        }
    }

    // ---------- 라운드 시작 (§3, §4) ----------

    /**
     * 덱을 새로 섞어 분배하고 입찰 단계로 들어간다.
     *
     * @param rng 테스트에서 시드를 고정할 수 있도록 주입받는다. 운영 경로는
     *            {@code SecureRandom} 을 넘긴다 (§13-⑯ 의 "무작위 + 시드 로깅")
     */
    public Result startRound(SkullKingMatchState match, Random rng) {
        int seatCount = context.seatCount();
        // S5 배선 오류 조기 검출 — 방 인원과 매치 참가자 수가 어긋난 채 분배하면 이후
        // 모든 좌석 산술이 조용히 틀린다. (테스트용 빈 맵은 예외적으로 허용.)
        if (!match.cumulativeScores().isEmpty()
                && match.cumulativeScores().size() != seatCount) {
            throw new IllegalStateException("Match participants " + match.cumulativeScores().size()
                    + " != room seats " + seatCount);
        }
        int roundNumber = match.roundNumber();
        List<PlayerState> players = Dealer.deal(roundNumber, seatCount, rng);
        int handSize = Dealer.handSize(roundNumber, seatCount);

        List<SkullKingEvent> events = new ArrayList<>();
        events.add(new SkullKingEvent.BiddingStarted(roundNumber, handSize));
        for (PlayerState player : players) {
            events.add(new SkullKingEvent.HandDealt(player.seat(), player.hand(), roundNumber));
        }
        return new Result(
                new SkullKingState.Bidding(roundNumber, players, match.startSeat()), events);
    }

    // ---------- 액션 적용 ----------

    public Result apply(SkullKingState state, int seat, SkullKingAction action) {
        ActionValidator.validate(state, seat, action);
        return switch (action) {
            case SkullKingAction.PlaceBid bid -> applyPlaceBid((SkullKingState.Bidding) state, seat, bid);
            case SkullKingAction.PlayCard play -> applyPlayCard((SkullKingState.Playing) state, seat, play);
        };
    }

    /** §5 — 순차로 받되 전원 제출 전까지 값을 공개하지 않는다. */
    private Result applyPlaceBid(SkullKingState.Bidding state, int seat,
                                 SkullKingAction.PlaceBid action) {
        List<PlayerState> players = replace(state.players(), seat,
                p -> p.withBid(action.bid()));

        List<SkullKingEvent> events = new ArrayList<>();
        events.add(new SkullKingEvent.BidSubmitted(seat));

        SkullKingState.Bidding updated =
                new SkullKingState.Bidding(state.roundNumber(), players, state.startSeat());
        if (!updated.allBidsIn()) {
            return new Result(updated, events);
        }

        // 전원 제출 — 여기서 처음으로 값이 공개된다. (BidsRevealed 의 Map.copyOf 가 순회
        // 순서를 보존하지 않으므로 순서 있는 맵을 쓰지 않는다 — 소비자는 좌석 키 조회만.)
        Map<Integer, Integer> bids = new HashMap<>();
        players.forEach(p -> bids.put(p.seat(), p.bid()));
        events.add(new SkullKingEvent.BidsRevealed(bids));
        events.add(new SkullKingEvent.PlayingStarted(state.startSeat()));
        events.add(new SkullKingEvent.TurnChanged(state.startSeat()));

        return new Result(new SkullKingState.Playing(state.roundNumber(), players,
                state.startSeat(), TrickState.lead(state.startSeat())), events);
    }

    /** §6~§9 — 카드 제출, 트릭 완성 시 즉시 정산. */
    private Result applyPlayCard(SkullKingState.Playing state, int seat,
                                 SkullKingAction.PlayCard action) {
        int seatCount = state.seatCount();
        PlayedCard played = new PlayedCard(seat, action.card(), action.declaredAs());

        List<PlayerState> players = replace(state.players(), seat,
                p -> p.withoutCard(action.card()));
        TrickState trick = state.trick().with(played);

        List<SkullKingEvent> events = new ArrayList<>();
        events.add(new SkullKingEvent.CardPlayed(seat, action.card(), action.declaredAs()));

        if (!trick.isComplete(seatCount)) {
            events.add(new SkullKingEvent.TurnChanged(trick.currentTurnSeat(seatCount)));
            return new Result(new SkullKingState.Playing(state.roundNumber(), players,
                    state.startSeat(), trick), events);
        }

        // 트릭 완성 — 승자가 카드 전부를 가져간다 (§9).
        TrickResult result = TrickResolver.resolve(trick.played());
        players = replace(players, result.winnerSeat(), p -> p.withTrickWon(result));
        // 방금 적립분 포함 전체 승수 합 = 이번이 몇 번째 트릭인가 (트릭마다 정확히 1 증가).
        int trickNumber = players.stream().mapToInt(PlayerState::tricksWonCount).sum();
        events.add(new SkullKingEvent.TrickTaken(
                result.winnerSeat(), result.winningCard().card(), trickNumber));

        boolean handsExhausted = players.stream().allMatch(p -> p.hand().isEmpty());
        if (!handsExhausted) {
            // 직전 트릭 승자가 다음 트릭을 리드한다 (§3).
            events.add(new SkullKingEvent.TurnChanged(result.winnerSeat()));
            return new Result(new SkullKingState.Playing(state.roundNumber(), players,
                    state.startSeat(), TrickState.lead(result.winnerSeat())), events);
        }

        Map<Integer, RoundScore> scores = RoundScorer.scoreAll(players, state.roundNumber());
        return new Result(new SkullKingState.RoundEnd(state.roundNumber(), players,
                state.startSeat(), scores), events);
    }

    // ---------- 탈주 (D-104, §13-⑱⑲⑳) ----------

    /**
     * 좌석 탈주 확정 — "남은 사람끼리 계속". 좌석은 제거하지 않고 매치에 유령 표식만 남긴
     * 뒤, 계속이면 그 좌석의 대기 차례를 즉시 드레인해 진행을 재개시킨다.
     *
     * <p>멱등: 이미 끝난 매치·이미 탈주한 좌석·매치 참가자가 아닌 좌석은
     * {@link Desertion.Outcome#NOT_APPLICABLE} (상태 무변경, 이벤트 0건).
     *
     * @param humanSeats 사람이 점유한 좌석들(탈주자 포함). 잔존 사람 0 판정에만 쓴다 —
     *                   봇 좌석의 탈주 처리 여부는 인프라 책임이다
     */
    public Desertion desert(SkullKingState state, SkullKingMatchState match,
                            int seat, Set<Integer> humanSeats) {
        if (match.isMatchOver()
                || match.desertedSeats().contains(seat)
                || !match.cumulativeScores().containsKey(seat)) {
            return new Desertion(state, match, Desertion.Outcome.NOT_APPLICABLE, List.of());
        }
        SkullKingMatchState next = match.withSeatDeserted(seat);
        List<SkullKingEvent> events = new ArrayList<>();
        events.add(new SkullKingEvent.SeatDeserted(seat));

        boolean humanLeft = humanSeats.stream().anyMatch(s -> !next.desertedSeats().contains(s));
        if (next.activeSeats().size() < SkullKingMatchState.MIN_SEATS_TO_CONTINUE || !humanLeft) {
            // 조기 종료 — 진행 중 라운드는 점수 미반영 폐기 (§13-⑲). 완주 수는 점프 전에 읽는다.
            int roundsPlayed = next.roundNumber() - 1;
            SkullKingMatchState ended = next.abandoned();
            events.add(new SkullKingEvent.MatchEnded(
                    ended.winners(), ended.cumulativeScores(), roundsPlayed));
            return new Desertion(state, ended, Desertion.Outcome.MATCH_ENDED, events);
        }
        SkullKingState drained = drainGhosts(state, next.desertedSeats(), events);
        return new Desertion(drained, next, Desertion.Outcome.CONTINUED, events);
    }

    /**
     * 사람 액션 적용 + 유령 차례 드레인. 탈주 좌석의 사람 액션은
     * {@link RejectionReason#SEAT_DESERTED} 로 거절한다 — 유예 중 재접속한 stale 클라의
     * 늦은 액션이 자동조종과 경합하는 것을 막는다.
     *
     * <p>탈주가 있는 방의 어댑터는 {@code apply} 대신 반드시 이걸 쓴다 — 드레인을 빠뜨리면
     * 유령 차례에서 예외 없이 조용히 멈춘다 (2-인자 불변식 체커가 그 상태를 잡는다).
     */
    public Result applyAndDrain(SkullKingState state, SkullKingMatchState match,
                                int seat, SkullKingAction action) {
        if (match.desertedSeats().contains(seat)) {
            throw new SkullKingActionRejectedException(
                    RejectionReason.SEAT_DESERTED, "seat " + seat);
        }
        Result applied = apply(state, seat, action);
        List<SkullKingEvent> events = new ArrayList<>(applied.events());
        SkullKingState drained = drainGhosts(applied.newState(), match.desertedSeats(), events);
        return new Result(drained, events);
    }

    /** 라운드 시작 + 유령 입찰 드레인 — 새 라운드가 유령에서 막히지 않게 한다 (§13-⑱). */
    public Result startRoundAndDrain(SkullKingMatchState match, Random rng) {
        Result started = startRound(match, rng);
        List<SkullKingEvent> events = new ArrayList<>(started.events());
        SkullKingState drained = drainGhosts(started.newState(), match.desertedSeats(), events);
        return new Result(drained, events);
    }

    /**
     * 대기 좌석이 사람에게 돌아오거나 라운드가 끝날 때까지 유령의 액션을 결정적으로
     * 적용한다. 정책은 {@link #timeoutAction} 과 공유(최약수) — 두 경로 불일치 회귀를
     * 원천 차단한다.
     */
    private SkullKingState drainGhosts(SkullKingState state, Set<Integer> deserted,
                                       List<SkullKingEvent> events) {
        if (deserted.isEmpty()) {
            return state;
        }
        while (true) {
            List<Integer> ghosts = pendingSeats(state).stream()
                    .filter(deserted::contains)
                    .toList();
            if (ghosts.isEmpty()) {
                return state;
            }
            int seat = ghosts.get(0);
            SkullKingAction auto = timeoutAction(state, seat);
            if (auto == null) {
                // 도달 불가 방어선 — 대기 좌석은 항상 합법수가 있다 (입찰 0은 무조건,
                // 플레이는 특수 카드 상시 합법 + follow 필터가 공집합을 만들지 않음).
                return state;
            }
            Result applied = apply(state, seat, auto);
            state = applied.newState();
            events.addAll(applied.events());
        }
    }

    // ---------- 라운드 정산 (§10, §12) ----------

    /**
     * 라운드 점수를 매치에 누적하고, 10라운드를 다 돌았으면 매치 종료 이벤트까지 낸다.
     *
     * <p>{@code RoundEnded} 를 여기서 내는 것은 매치 누적 점수를 함께 실어야 하기 때문이다 —
     * {@code apply} 는 매치 상태를 모른다 (라운드 상태만 다룬다).
     */
    public Settlement settleRound(SkullKingState.RoundEnd state, SkullKingMatchState match) {
        SkullKingMatchState next = match.withRoundScored(state.totalsBySeat(), state.seatCount());

        List<SkullKingEvent> events = new ArrayList<>();
        events.add(new SkullKingEvent.RoundEnded(
                state.roundNumber(), state.scores(), next.cumulativeScores()));
        if (next.isMatchOver()) {
            events.add(new SkullKingEvent.MatchEnded(
                    next.winners(), next.cumulativeScores(), SkullKingMatchState.TOTAL_ROUNDS));
        }
        return new Settlement(next, events);
    }

    // ---------- 진행 질의 ----------

    /**
     * 지금 행동을 기다리는 좌석들 — 오름차순, 비면 대기 없음.
     *
     * <p>복수인 이유는 입찰이 <b>동시 대기</b>라서다 (D-98 이 티츄 Dealing/Passing 에서 만난
     * 것과 같은 문제). 단수로 두면 좌석 3 의 봇이 좌석 1 사람의 입찰을 기다리며 멈춘다.
     */
    public List<Integer> pendingSeats(SkullKingState state) {
        return switch (state) {
            case SkullKingState.Bidding bidding -> bidding.awaitingSeats();
            case SkullKingState.Playing playing -> {
                int turn = playing.currentTurnSeat();
                yield turn < 0 ? List.of() : List.of(turn);
            }
            case SkullKingState.RoundEnd __ -> List.of();
        };
    }

    public boolean isRoundOver(SkullKingState state) {
        return state instanceof SkullKingState.RoundEnd;
    }

    // ---------- 봇 / 타임아웃 ----------

    /** 해당 좌석이 지금 취할 수 있는 액션 전부. 비어 있으면 할 수 있는 게 없다. */
    public List<SkullKingAction> legalActions(SkullKingState state, int seat) {
        if (seat < 0 || seat >= state.seatCount()) {
            return List.of();
        }
        return switch (state) {
            case SkullKingState.Bidding bidding -> legalBidActions(bidding, seat);
            case SkullKingState.Playing playing -> legalPlayActions(playing, seat);
            case SkullKingState.RoundEnd __ -> List.of();
        };
    }

    private List<SkullKingAction> legalBidActions(SkullKingState.Bidding state, int seat) {
        PlayerState player = state.players().get(seat);
        if (player.hasBid()) {
            return List.of();
        }
        return BidRules.legalBids(player.handSize()).stream()
                .<SkullKingAction>map(SkullKingAction.PlaceBid::new)
                .toList();
    }

    /**
     * 손패를 follow 의무로 거른다. 리드 수트가 트릭 도중 확정될 수 있어 <b>합법수 집합이
     * 트릭 진행 중에 바뀐다</b> (명세 함정 #1) — 그래서 매 호출마다 현재 트릭으로 다시 센다.
     */
    private List<SkullKingAction> legalPlayActions(SkullKingState.Playing state, int seat) {
        if (state.currentTurnSeat() != seat) {
            return List.of();
        }
        PlayerState player = state.players().get(seat);
        List<SkullKingAction> actions = new ArrayList<>();
        for (SkullCard card : distinct(player.hand())) {
            if (!ActionValidator.followsLeadSuit(state.trick(), player.hand(), card)) {
                continue;
            }
            if (card.is(SpecialKind.TIGRESS)) {
                // 같은 카드가 두 액션이 된다 — 선언이 정체성을 바꾸므로 (§13-②).
                actions.add(SkullKingAction.PlayCard.tigress(TigressMode.PIRATE));
                actions.add(SkullKingAction.PlayCard.tigress(TigressMode.ESCAPE));
            } else {
                actions.add(SkullKingAction.PlayCard.of(card));
            }
        }
        return List.copyOf(actions);
    }

    /**
     * 턴 제한 초과·유령 자동조종(D-104)이 <b>공유하는 결정적 최약수</b> 정책. 없으면 null.
     *
     * <p>입찰은 0 예측 — 손패와 무관하게 항상 합법이다. 플레이는 합법수 중 가장 약한 카드
     * (탈출 > 비검정 저랭크 > 검정 저랭크 > 인어 > 해적 > 스컬킹, 티그리스는 탈출 선언) —
     * 대신 두는 수가 트릭을 이겨 판을 흔드는 빈도를 최소화한다 (§13-⑱).
     */
    public SkullKingAction timeoutAction(SkullKingState state, int seat) {
        if (seat < 0 || seat >= state.seatCount()) {
            return null;
        }
        if (state instanceof SkullKingState.Bidding bidding
                && !bidding.players().get(seat).hasBid()) {
            return new SkullKingAction.PlaceBid(BidRules.MIN_BID);
        }
        return legalActions(state, seat).stream()
                .min(Comparator.comparingInt(SkullKingEngine::weakness))
                .orElse(null);
    }

    /** 최약수 전순서 — 낮을수록 약하다. 동률은 손패 순서상 앞선 액션 (min 이 첫 최소 유지). */
    private static int weakness(SkullKingAction action) {
        if (!(action instanceof SkullKingAction.PlayCard play)) {
            return Integer.MAX_VALUE;
        }
        if (play.card().is(SpecialKind.ESCAPE) || play.declaredAs() == TigressMode.ESCAPE) {
            return 0;
        }
        SkullCard card = play.card();
        if (card.isSuit()) {
            return card.isTrump() ? 200 + card.rank() : 100 + card.rank();
        }
        return switch (card.special()) {
            case MERMAID -> 300;
            case TIGRESS, PIRATE -> 400;   // 해적 선언 티그리스 = 해적 (탈출 선언은 위에서 0)
            case SKULL_KING -> 500;
            case ESCAPE -> 0;              // 위에서 걸렸지만 switch 완전성
        };
    }

    // ---------- helpers ----------

    private static List<PlayerState> replace(List<PlayerState> players, int seat,
                                             java.util.function.UnaryOperator<PlayerState> op) {
        List<PlayerState> next = new ArrayList<>(players);
        next.set(seat, op.apply(next.get(seat)));
        return List.copyOf(next);
    }

    /** 같은 값의 카드가 여러 장이면 액션은 하나면 된다 (교환 가능하므로). */
    private static List<SkullCard> distinct(List<SkullCard> hand) {
        return hand.stream().distinct().toList();
    }
}
