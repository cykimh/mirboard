package com.mirboard.domain.game.skullking;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.action.ActionValidator;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    // ---------- 라운드 시작 (§3, §4) ----------

    /**
     * 덱을 새로 섞어 분배하고 입찰 단계로 들어간다.
     *
     * @param rng 테스트에서 시드를 고정할 수 있도록 주입받는다. 운영 경로는
     *            {@code SecureRandom} 을 넘긴다 (§13-⑯ 의 "무작위 + 시드 로깅")
     */
    public Result startRound(SkullKingMatchState match, Random rng) {
        int seatCount = context.seatCount();
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

        // 전원 제출 — 여기서 처음으로 값이 공개된다.
        Map<Integer, Integer> bids = new LinkedHashMap<>();
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
        int trickNumber = trickNumberOf(state);
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

    /** 이번이 이 라운드의 몇 번째 트릭인가 (1부터). 손패 소진량으로 역산한다. */
    private int trickNumberOf(SkullKingState.Playing state) {
        int total = Dealer.handSize(state.roundNumber(), state.seatCount());
        int remainingBeforeThisPlay = state.players().stream()
                .mapToInt(PlayerState::handSize).max().orElse(0);
        return total - remainingBeforeThisPlay + 1;
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
     * 턴 제한 초과 시 적용할 <b>결정적</b> 안전 액션. 없으면 null.
     *
     * <p>입찰은 0 예측 — 손패와 무관하게 항상 합법이다. 플레이는 합법수 중 첫 번째(손패
     * 순서 기준)이고, 티그리스는 탈출로 선언한다 (반드시 지므로 남의 판을 흔들지 않는다).
     */
    public SkullKingAction timeoutAction(SkullKingState state, int seat) {
        if (state instanceof SkullKingState.Bidding bidding
                && !bidding.players().get(seat).hasBid()) {
            return new SkullKingAction.PlaceBid(BidRules.MIN_BID);
        }
        List<SkullKingAction> legal = legalActions(state, seat);
        return legal.isEmpty() ? null : legal.get(legal.size() == 1 ? 0 : preferSafe(legal));
    }

    /** 탈출/탈출 선언 티그리스가 있으면 그것을, 없으면 첫 합법수를. */
    private int preferSafe(List<SkullKingAction> legal) {
        for (int i = 0; i < legal.size(); i++) {
            if (legal.get(i) instanceof SkullKingAction.PlayCard play
                    && (play.card().is(SpecialKind.ESCAPE)
                        || play.declaredAs() == TigressMode.ESCAPE)) {
                return i;
            }
        }
        return 0;
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
