package com.mirboard.domain.game.yacht;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.yacht.action.RejectionReason;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.action.YachtActionRejectedException;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.rules.Ranking;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.score.Scoring;
import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.TurnState;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * 요트 순수 룰 엔진 (`docs/rules-yacht.md`). 저장소·시계·난수기가 없다 — 시각과 {@link DiceRoller} 는 인자로 받는다.
 * 포트 어댑터(S3)가 이것을 감싸 저장·로컬 발행·엔진 타이머를 붙인다(D-98 2계층).
 *
 * <p>모든 전이는 새 {@link YachtState} 와 그 전이의 공개 이벤트 목록을 돌려준다. 거절은
 * {@link YachtActionRejectedException} 이고 상태를 바꾸지 않는다.
 *
 * <p><b>D-202 동시 진행</b>(방 옵션 {@code TURN_MODE=SIMULTANEOUS}, 2인 이상): 살아 있는 모두가 같은 라운드를 동시에 굴리고, 전원이
 * 기록하면 다음 라운드({@code ROUND_STARTED}). 차례 전체 제한은 라운드 전체 하나이고 만기면 아직 안 한 사람 모두 자동 기록한다.
 */
public final class YachtEngine {

    private final int seatCount;
    private final Set<Integer> botSeats;
    private final int turnSeconds;
    private final boolean simultaneous;

    /**
     * 좌석 수·봇 좌석·차례 전체 제한({@code turnSeconds}, 0 이면 끔 — §6, D-183)·진행 방식({@code TURN_MODE}, D-202 — 1인 판은 늘
     * 차례대로)을 방 설정에서 받는다.
     */
    public YachtEngine(GameContext context) {
        if (context.seatCount() < 1) {
            throw new IllegalArgumentException("Yacht needs at least one seat");
        }
        this.seatCount = context.seatCount();
        this.botSeats = Collections.unmodifiableSet(new TreeSet<>(context.botSeats()));
        this.turnSeconds = context.turnSeconds();
        this.simultaneous = context.seatCount() >= 2 && "SIMULTANEOUS".equals(context.option(RoomOption.TURN_MODE));
    }

    public boolean simultaneous() {
        return simultaneous;
    }

    public record Result(YachtState newState, List<YachtEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    public record Desertion(YachtState newState, Outcome outcome, List<YachtEvent> events) {

        public enum Outcome {
            /** 이미 탈주했거나 끝난 매치 — 아무것도 바뀌지 않았다. */
            NOT_APPLICABLE,
            MATCH_CONTINUES,
            MATCH_ENDED
        }

        public Desertion {
            events = List.copyOf(events);
        }
    }

    public int turnSeconds() {
        return turnSeconds;
    }

    // ---- 시작 (§3) ----

    /** 첫 라운드, 좌석 0 차례(§3-2). 동시 진행이면 모두의 첫 라운드(D-202). */
    public Result start(long now) {
        List<Scorecard> cards = new ArrayList<>(Collections.nCopies(seatCount, Scorecard.EMPTY));
        if (simultaneous) {
            YachtState state = new YachtState(cards, 1, -1, TurnState.fresh(now), List.of(), 0, null, true,
                    Collections.nCopies(seatCount, TurnState.fresh(now)));
            return new Result(state, List.of(new YachtEvent.RoundStarted(1, turnSeconds)));
        }
        YachtState state = new YachtState(cards, 1, 0, TurnState.fresh(now), List.of(), 0, null);
        return new Result(state, List.of(new YachtEvent.TurnChanged(0, 1, turnSeconds)));
    }

    // ---- 액션 (§4) ----

    public Result apply(YachtState state, int seat, YachtAction action, DiceRoller roller, long now) {
        if (state.ended()) {
            throw reject(RejectionReason.MATCH_OVER);
        }
        if (!state.pending(seat)) {
            throw reject(RejectionReason.NOT_YOUR_TURN);
        }
        if (action == null) {
            throw reject(RejectionReason.INVALID_ACTION);
        }
        Result result = switch (action) {
            case YachtAction.Roll roll -> roll(state, seat, roll.held(), roller);
            case YachtAction.Hold hold -> hold(state, seat, hold.held());
            case YachtAction.Score score -> score(state, seat, score.category(), false, now, List.of());
        };
        return countAction(result);
    }

    private Result roll(YachtState state, int seat, HoldMask requested, DiceRoller roller) {
        TurnState turn = state.turnOf(seat);
        if (turn.rolls() >= TurnState.MAX_ROLLS) {
            throw reject(RejectionReason.NO_ROLLS_LEFT);
        }
        List<YachtEvent> events = new ArrayList<>(2);
        Dice dice;
        HoldMask held;
        if (turn.rolls() == 0) {
            if (requested != null && !requested.none()) {
                throw reject(RejectionReason.HOLD_NOT_ALLOWED);
            }
            held = HoldMask.NONE;
            dice = Dice.rollAll(roller);
        } else {
            held = requested == null ? turn.held() : requested;
            if (held.all()) {
                throw reject(RejectionReason.NOTHING_TO_ROLL);
            }
            if (!held.equals(turn.held())) {
                events.add(new YachtEvent.DiceHeld(seat, held));
            }
            dice = turn.dice().reroll(held, roller);
        }
        int rolls = turn.rolls() + 1;
        YachtState next = state.withTurnOf(seat, new TurnState(dice, held, rolls, turn.startedAt()));
        events.add(new YachtEvent.DiceRolled(seat, dice, held, rolls, Scoring.candidates(state.card(seat), dice)));
        return new Result(next, events);
    }

    private Result hold(YachtState state, int seat, HoldMask held) {
        if (held == null) {
            throw reject(RejectionReason.INVALID_ACTION);
        }
        TurnState turn = state.turnOf(seat);
        if (turn.rolls() == 0 || turn.rolls() >= TurnState.MAX_ROLLS) {
            throw reject(RejectionReason.HOLD_NOT_ALLOWED);
        }
        if (held.equals(turn.held())) {
            throw reject(RejectionReason.HOLD_UNCHANGED);
        }
        YachtState next = state.withTurnOf(seat, new TurnState(turn.dice(), held, turn.rolls(), turn.startedAt()));
        return new Result(next, List.of(new YachtEvent.DiceHeld(seat, held)));
    }

    private Result score(YachtState state, int seat, Category category, boolean auto, long now,
                         List<YachtEvent> before) {
        if (category == null) {
            throw reject(RejectionReason.INVALID_ACTION);
        }
        TurnState turn = state.turnOf(seat);
        if (turn.rolls() == 0) {
            throw reject(RejectionReason.NOT_ROLLED_YET);
        }
        Scorecard card = state.card(seat);
        if (card.filled(category)) {
            throw reject(RejectionReason.CATEGORY_FILLED);
        }
        int points = Scoring.points(category, turn.dice());
        Scorecard nextCard = card.with(category, points);
        List<Scorecard> cards = new ArrayList<>(state.cards());
        cards.set(seat, nextCard);
        YachtState scored = state.withCards(cards);

        List<YachtEvent> events = new ArrayList<>(before);
        events.add(new YachtEvent.Scored(seat, category, points, nextCard.upperSubtotal(), nextCard.bonus(),
                nextCard.total(), auto));
        return endTurn(scored, seat, now, events);
    }

    /** 성공한 액션 하나를 세고, 상한에 닿았으면 그 자리에서 끝낸다(§8.1 {@code ACTION_LIMIT}). */
    private Result countAction(Result result) {
        YachtState s = result.newState();
        YachtState counted = s.withActionCount(s.actionCount() + 1);
        if (counted.ended() || counted.actionCount() < counted.actionLimit()) {
            return new Result(counted, result.events());
        }
        // 이 전이가 낸 다음 차례 알림(있으면)도 그대로 둔다 — 라운드 번호가 공개 뷰와 맞는다.
        return finish(counted, EndReason.ACTION_LIMIT, result.events());
    }

    // ---- 차례 넘김·종료 (§3·§8) ----

    /** 좌석 {@code seat} 의 차례가 끝났다(기록 또는 탈주). 다음 좌석·라운드 또는 종료. */
    private Result endTurn(YachtState state, int seat, long now, List<YachtEvent> events) {
        if (allAliveFull(state)) {
            return finish(state, EndReason.FINISHED, events);
        }
        if (state.simultaneous()) {
            // D-202 — 이 좌석의 주사위를 비우고, 아직 기록하지 않은 사람이 있으면 기다린다. 모두 했으면 다음 라운드.
            YachtState done = state.withTurnOf(seat, TurnState.none());
            return done.pendingSeats().isEmpty() ? nextRound(done, now, events) : new Result(done, events);
        }
        int nextSeat = -1;
        for (int s = seat + 1; s < state.seatCount(); s++) {
            if (state.alive(s)) {
                nextSeat = s;
                break;
            }
        }
        int round = state.round();
        if (nextSeat < 0) {
            round++;
            nextSeat = state.aliveSeats().get(0);
        }
        YachtState next = new YachtState(state.cards(), round, nextSeat, TurnState.fresh(now), state.deserted(),
                state.actionCount(), null);
        List<YachtEvent> out = new ArrayList<>(events);
        out.add(new YachtEvent.TurnChanged(nextSeat, round, turnSeconds));
        return new Result(next, out);
    }

    /** D-202 — 동시 진행의 다음 라운드: 살아 있는 모두에게 새 주사위, 라운드 시계를 지금으로. */
    private Result nextRound(YachtState state, long now, List<YachtEvent> events) {
        int round = state.round() + 1;
        List<TurnState> turns = new ArrayList<>(state.seatCount());
        for (int s = 0; s < state.seatCount(); s++) {
            turns.add(state.alive(s) ? TurnState.fresh(now) : TurnState.none());
        }
        YachtState next = new YachtState(state.cards(), round, -1, TurnState.fresh(now), state.deserted(),
                state.actionCount(), null, true, turns);
        List<YachtEvent> out = new ArrayList<>(events);
        out.add(new YachtEvent.RoundStarted(round, turnSeconds));
        return new Result(next, out);
    }

    private static boolean allAliveFull(YachtState state) {
        List<Integer> alive = state.aliveSeats();
        return !alive.isEmpty() && alive.stream().allMatch(s -> state.card(s).isFull());
    }

    private Result finish(YachtState state, EndReason reason, List<YachtEvent> events) {
        List<TurnState> turns = state.simultaneous()
                ? Collections.nCopies(state.seatCount(), TurnState.none())
                : List.of();
        YachtState base = new YachtState(state.cards(), state.round(), -1, TurnState.none(), state.deserted(),
                state.actionCount(), null, state.simultaneous(), turns);
        MatchResult result = Ranking.result(base, reason);
        YachtState ended = new YachtState(base.cards(), base.round(), -1, base.turn(), base.deserted(),
                base.actionCount(), result, base.simultaneous(), turns);
        List<YachtEvent> out = new ArrayList<>(events);
        out.add(new YachtEvent.MatchEnded(reason, result.placements()));
        return new Result(ended, out);
    }

    // ---- 차례 제한 (§6) ----

    /** 차례 마감까지 남은 시간(ms) — 0 이하면 만기. 제한이 꺼졌거나 끝난 매치면 empty. 동시 진행이면 라운드 마감(D-202). */
    public OptionalLong remainingMillis(YachtState state, long now) {
        if (turnSeconds <= 0 || state.ended()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(state.turn().startedAt() + turnSeconds * 1000L - now);
    }

    /**
     * 차례 마감의 자동 처리(§6-2) — 안 굴렸으면 한 번 굴리고, 점수가 가장 높은 빈 칸(동점은 위 칸)에 기록한다. 시각을 다시 보지
     * 않는다(만기 판정은 {@link #remainingMillis} 로 부르는 쪽이 한다). 끝난 매치에는 쓸 수 없다. 액션 수에 넣지 않는다.
     */
    public Result expire(YachtState state, DiceRoller roller, long now) {
        if (state.ended()) {
            throw new IllegalStateException("Match already ended");
        }
        // 동시 진행(D-202)이면 아직 기록하지 않은 사람 모두, 좌석 순으로. 마지막 사람의 기록이 다음 라운드를 연다.
        Result current = new Result(state, List.of());
        for (int seat : state.pendingSeats()) {
            current = expireSeat(current.newState(), seat, roller, now, current.events());
        }
        return current;
    }

    private Result expireSeat(YachtState state, int seat, DiceRoller roller, long now, List<YachtEvent> before) {
        YachtState current = state;
        List<YachtEvent> events = new ArrayList<>(before);
        if (state.turnOf(seat).rolls() == 0) {
            Result rolled = roll(state, seat, null, roller);
            current = rolled.newState();
            events.addAll(rolled.events());
        }
        Category best = Scoring.best(current.card(seat), current.turnOf(seat).dice());
        return score(current, seat, best, true, now, events);
    }

    /** 결정적 폴백 수(§6-4) — 포트 {@code timeoutAction}·봇 정책 실패. */
    public YachtAction fallbackAction(YachtState state, int seat) {
        TurnState turn = state.turnOf(seat);
        if (turn.rolls() == 0) {
            return YachtAction.Roll.plain();
        }
        return new YachtAction.Score(Scoring.best(state.card(seat), turn.dice()));
    }

    // ---- 탈주 (§7) ----

    public Desertion desert(YachtState state, int seat, long now) {
        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
            return new Desertion(state, Desertion.Outcome.NOT_APPLICABLE, List.of());
        }
        List<Integer> deserted = new ArrayList<>(state.deserted());
        deserted.add(seat);
        boolean wasTurn = !state.simultaneous() && seat == state.turnSeat();
        List<TurnState> turns = state.seatTurns();
        if (state.simultaneous()) {
            turns = new ArrayList<>(turns);
            turns.set(seat, TurnState.none());
        }
        YachtState marked = new YachtState(state.cards(), state.round(), state.turnSeat(),
                wasTurn ? TurnState.none() : state.turn(), deserted, state.actionCount(), null,
                state.simultaneous(), turns);

        List<YachtEvent> events = new ArrayList<>();
        events.add(new YachtEvent.PlayerEliminated(seat, state.card(seat).total()));

        Result result;
        if (allAliveFull(marked)) {
            result = finish(marked, EndReason.FINISHED, events);
        } else if (marked.aliveSeats().stream().noneMatch(s -> !botSeats.contains(s))) {
            result = finish(marked, EndReason.NO_HUMANS, events);
        } else if (wasTurn) {
            result = endTurn(marked, seat, now, events);
        } else if (marked.simultaneous() && marked.pendingSeats().isEmpty()) {
            // D-202 — 남은 사람이 이번 라운드를 다 기록했다(탈주자만 남아 있었다) — 바로 다음 라운드.
            result = nextRound(marked, now, events);
        } else {
            result = new Result(marked, events);
        }
        Desertion.Outcome outcome = result.newState().ended()
                ? Desertion.Outcome.MATCH_ENDED
                : Desertion.Outcome.MATCH_CONTINUES;
        return new Desertion(result.newState(), outcome, result.events());
    }

    // ---- 진행 질의 ----

    public List<Integer> pendingSeats(YachtState state) {
        return state.pendingSeats();
    }

    /** 합법 액션 전부 — 최대 45개(홀드 ≤ 31 + 굴리기 1 + 빈 칸 ≤ 12). 차례가 아니면 빈 목록. */
    public List<YachtAction> legalActions(YachtState state, int seat) {
        if (!state.pending(seat)) {
            return List.of();
        }
        TurnState turn = state.turnOf(seat);
        if (turn.rolls() == 0) {
            return List.of(YachtAction.Roll.plain());
        }
        List<YachtAction> out = new ArrayList<>();
        if (turn.rolls() < TurnState.MAX_ROLLS) {
            for (HoldMask mask : HoldMask.every()) {
                if (!mask.equals(turn.held())) {
                    out.add(new YachtAction.Hold(mask));
                }
            }
            if (!turn.held().all()) {
                out.add(YachtAction.Roll.plain());
            }
        }
        Scorecard card = state.card(seat);
        for (Category c : Category.values()) {
            if (!card.filled(c)) {
                out.add(new YachtAction.Score(c));
            }
        }
        return List.copyOf(out);
    }

    public Set<Integer> botSeats() {
        return botSeats;
    }

    private static YachtActionRejectedException reject(RejectionReason reason) {
        return new YachtActionRejectedException(reason);
    }
}
