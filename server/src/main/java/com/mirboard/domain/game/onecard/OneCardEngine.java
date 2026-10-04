package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardActionRejectedException;
import com.mirboard.domain.game.onecard.action.RejectionReason;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.DrawReason;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.rules.PlayRules;
import com.mirboard.domain.game.onecard.rules.Ranking;
import com.mirboard.domain.game.onecard.rules.TurnOrder;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;

/**
 * 원카드 <b>순수 룰 엔진</b> (`docs/rules-onecard.md`).
 *
 * <p>Spring·Redis·시계를 모른다. 시각이 필요한 곳(경쟁 창을 여는 순간)은 {@code now} 를 인자로 받고, 난수는
 * 생성자로 주입받는다 — 테스트는 시드를 고정한다. 포트 어댑터·상태 저장·뷰·봇 정책·기록은 S3 범위다(스컬킹의
 * {@code SkullKingEngine} + {@code SkullKingGameEngine} 과 같은 2계층).
 *
 * <p>상태는 불변 레코드다. 한 전이가 여러 필드를 함께 바꾸므로 엔진 안에서만 가변 사본({@code Table})을 만들어
 * 고친 뒤 새 레코드로 얼린다 — 밖에서 보이는 것은 언제나 새 {@link OneCardState} 다.
 */
public final class OneCardEngine {

    /** 먹은 뒤 이 장수 이상이면 파산 (§7, §10). */
    public static final int BANKRUPTCY_HAND_SIZE = 20;

    /** 총 차례 상한 (§11.3). */
    public static final int TURN_LIMIT = 600;

    /** 경쟁 버튼 지터 범위 — −100~100 (§9, 설계서 §4.4). */
    public static final int JITTER_RANGE = 100;

    private final GameContext context;
    private final Random rng;
    private final RaceSettings settings;

    public OneCardEngine(GameContext context, Random rng, RaceSettings settings) {
        this.context = context;
        this.rng = rng;
        this.settings = settings;
    }

    public OneCardEngine(GameContext context, Random rng) {
        this(context, rng, RaceSettings.DEFAULT);
    }

    public GameContext context() {
        return context;
    }

    /** 전이 결과 — 새 상태 + 발행할 이벤트. */
    public record Result(OneCardState newState, List<OneCardEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    /** {@link #desert} 결과. */
    public record Desertion(OneCardState newState, Outcome outcome, List<OneCardEvent> events) {

        public enum Outcome {
            /** 이미 끝났거나 이미 탈락한 좌석 — 상태 무변경, 이벤트 0건 (§10). */
            NOT_APPLICABLE,
            /** 남은 사람끼리 계속. */
            CONTINUED,
            /** 탈락으로 1명만 남았거나 살아 있는 사람이 없어 끝났다 (§11.1). */
            MATCH_ENDED
        }

        public Desertion {
            events = List.copyOf(events);
        }
    }

    // ---------- 시작 (§2, §3) ----------

    public Result startMatch() {
        return startMatch(Dealer.random(rng));
    }

    /** 분배하고 첫 차례를 무작위로 고른다. 셔플러는 §3-5 테스트를 위해 주입할 수 있다. */
    public Result startMatch(Dealer.Shuffler shuffler) {
        int seatCount = context.seatCount();
        Dealer.Deal deal = Dealer.deal(seatCount, shuffler);
        int first = rng.nextInt(seatCount);
        OneCardState state = new OneCardState(deal.hands(), deal.drawPile(), List.of(deal.startCard()),
                first, +1, null, 0, null, List.of(), 0, 0, 1, null);

        List<OneCardEvent> events = new ArrayList<>();
        events.add(new OneCardEvent.MatchStarted(first, deal.startCard(), Dealer.HAND_SIZE, deal.drawPile().size()));
        for (int seat = 0; seat < seatCount; seat++) {
            events.add(new OneCardEvent.HandDealt(seat, deal.hands().get(seat), state.version()));
        }
        events.add(new OneCardEvent.TurnChanged(first, +1, 0));
        return new Result(state, events);
    }

    // ---------- 액션 (§4 ~ §9) ----------

    /**
     * @param now 지금 시각(epoch ms). 경쟁 창을 열 때만 쓴다
     * @throws OneCardActionRejectedException 룰 위반
     */
    public Result apply(OneCardState state, int seat, OneCardAction action, long now) {
        if (state.ended()) {
            throw rejected(RejectionReason.MATCH_OVER);
        }
        if (seat < 0 || seat >= state.seatCount()) {
            throw new IllegalArgumentException("no such seat: " + seat);
        }
        if (!state.alive(seat)) {
            throw rejected(RejectionReason.PLAYER_ELIMINATED);
        }
        return switch (action) {
            case OneCardAction.PlayCard play -> play(state, seat, play, now);
            case OneCardAction.Draw __ -> draw(state, seat);
            case OneCardAction.CallOneCard call -> press(state, seat, call.raceId(), true);
            case OneCardAction.Catch katch -> press(state, seat, katch.raceId(), false);
        };
    }

    private Result play(OneCardState state, int seat, OneCardAction.PlayCard action, long now) {
        requireTurn(state, seat);
        PlayingCard card = action.card();
        if (card == null || !state.hands().get(seat).contains(card)) {
            throw rejected(RejectionReason.CARD_NOT_OWNED);
        }
        if (card.isSuitChange() != (action.declaredSuit() != null)) {
            throw rejected(RejectionReason.INVALID_SUIT_DECLARATION);
        }
        if (!PlayRules.canPlay(state.topCard(), state.baseSuit(), state.attackStack(), card)) {
            throw rejected(state.attackStack() > 0
                    ? RejectionReason.COUNTER_REQUIRED : RejectionReason.CARD_NOT_PLAYABLE);
        }

        Table t = new Table(state);
        List<PlayingCard> hand = t.hands.get(seat);
        hand.remove(card);
        t.discardPile.add(card);
        t.declaredSuit = action.declaredSuit();
        if (card.isAttack()) {
            t.attackStack += card.attackValue();
        }
        if (card.isReverse()) {
            t.direction = -t.direction;
        }
        t.passStreak = 0;
        t.turnCount++;
        t.version++;

        List<OneCardEvent> events = new ArrayList<>();
        events.add(new OneCardEvent.CardPlayed(seat, card, t.declaredSuit, hand.size(), t.attackStack));
        events.add(new OneCardEvent.HandUpdated(seat, hand, List.of(), t.version));

        if (hand.isEmpty()) {
            finish(t, EndReason.FINISHED, seat, events);
        } else if (t.turnCount >= TURN_LIMIT) {
            finish(t, EndReason.STALEMATE, -1, events);
        } else {
            int next = TurnOrder.afterPlay(t.seatCount(), t::alive, seat, t.direction, card);
            if (hand.size() == 1) {
                openRace(t, seat, next, now, events);
            } else {
                passTurn(t, next, events);
            }
        }
        return new Result(t.freeze(), events);
    }

    private Result draw(OneCardState state, int seat) {
        requireTurn(state, seat);
        Table t = new Table(state);
        boolean attacked = t.attackStack > 0;
        List<OneCardEvent> events = new ArrayList<>();
        List<PlayingCard> drawn = drawFromPile(t, attacked ? t.attackStack : 1, events);
        List<PlayingCard> hand = t.hands.get(seat);
        hand.addAll(drawn);
        t.attackStack = 0;
        t.turnCount++;
        t.passStreak = drawn.isEmpty() ? t.passStreak + 1 : 0;
        t.version++;
        events.add(new OneCardEvent.CardsDrawn(seat, drawn.size(),
                attacked ? DrawReason.ATTACK : DrawReason.TURN, hand.size(), t.drawPile.size()));
        events.add(new OneCardEvent.HandUpdated(seat, hand, drawn, t.version));

        if (hand.size() >= BANKRUPTCY_HAND_SIZE) {
            eliminate(t, seat, Elimination.Reason.BANKRUPT, events);
            if (endIfDecided(t, events)) {
                return new Result(t.freeze(), events);
            }
        }
        if (t.passStreak >= t.aliveCount() || t.turnCount >= TURN_LIMIT) {
            finish(t, EndReason.STALEMATE, -1, events);
        } else {
            passTurn(t, TurnOrder.nextAlive(t.seatCount(), t::alive, seat, t.direction), events);
        }
        return new Result(t.freeze(), events);
    }

    private static void requireTurn(OneCardState state, int seat) {
        if (state.race() != null) {
            throw rejected(RejectionReason.RACE_IN_PROGRESS);
        }
        if (state.turnSeat() != seat) {
            throw rejected(RejectionReason.NOT_YOUR_TURN);
        }
    }

    // ---------- 외치기 경쟁 (§9) ----------

    private Result press(OneCardState state, int seat, int raceId, boolean call) {
        RaceWindow race = state.race();
        if (race == null || race.raceId() != raceId) {
            throw rejected(RejectionReason.NO_RACE);
        }
        if (call && seat != race.ownerSeat()) {
            throw rejected(RejectionReason.NOT_RACE_OWNER);
        }
        if (!call && seat == race.ownerSeat()) {
            throw rejected(RejectionReason.OWNER_CANNOT_CATCH);
        }
        return resolveRace(state, call ? RaceOutcome.CALLED : RaceOutcome.CAUGHT, seat);
    }

    /**
     * 경쟁 창이 저절로 닫히는 시각(epoch ms) — 가장 빠른 봇의 누름 또는 창 길이. 창이 없으면 비어 있다.
     * 포트의 {@code timer} 는 이 값에서 지금 시각을 빼 남은 시간을 만든다(S3).
     */
    public OptionalLong timerDeadline(OneCardState state) {
        if (state.ended() || state.race() == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(state.race().deadline());
    }

    /**
     * 경쟁 창의 시간 전이 — 추첨해 둔 봇의 누름이 창보다 빠르면 그 누름, 아니면 아무도 안 누른 채 닫힘(§9-4).
     * 발화를 믿는다: 시각을 다시 보지 않는다(설계서 §4.5).
     */
    public Optional<Result> onTimer(OneCardState state) {
        if (state.ended() || state.race() == null) {
            return Optional.empty();
        }
        RaceWindow.BotPress bot = state.race().botPress();
        if (bot == null) {
            return Optional.of(resolveRace(state, RaceOutcome.EXPIRED, -1));
        }
        return Optional.of(resolveRace(state, bot.call() ? RaceOutcome.CALLED : RaceOutcome.CAUGHT, bot.seat()));
    }

    private void openRace(Table t, int owner, int next, long now, List<OneCardEvent> events) {
        int raceId = t.version;
        int slot = rng.nextInt(settings.slotCount());
        int jitterX = rng.nextInt(2 * JITTER_RANGE + 1) - JITTER_RANGE;
        int jitterY = rng.nextInt(2 * JITTER_RANGE + 1) - JITTER_RANGE;
        t.race = new RaceWindow(raceId, owner, slot, jitterX, jitterY, now, settings.windowMillis(), next,
                fastestBot(t, owner));
        t.turnSeat = -1;
        events.add(new OneCardEvent.RaceOpened(raceId, owner, slot, jitterX, jitterY, settings.windowMillis()));
    }

    /** §9-6 — 살아 있는 봇마다 반응 시간을 뽑아 가장 빠른 한 명만 남긴다. 창보다 늦으면 없음. */
    private RaceWindow.BotPress fastestBot(Table t, int owner) {
        RaceWindow.BotPress fastest = null;
        for (int seat = 0; seat < t.seatCount(); seat++) {
            if (!t.alive(seat) || !context.botSeats().contains(seat)) {
                continue;
            }
            boolean call = seat == owner;
            long delay = call
                    ? rng.nextLong(settings.ownerMinMillis(), settings.ownerMaxMillis() + 1)
                    : rng.nextLong(settings.catcherMinMillis(), settings.catcherMaxMillis() + 1);
            if (fastest == null || delay < fastest.delayMillis()) {
                fastest = new RaceWindow.BotPress(seat, call, delay);
            }
        }
        return fastest != null && fastest.delayMillis() < settings.windowMillis() ? fastest : null;
    }

    private Result resolveRace(OneCardState state, RaceOutcome outcome, int bySeat) {
        Table t = new Table(state);
        RaceWindow race = t.race;
        t.race = null;
        t.version++;
        List<OneCardEvent> events = new ArrayList<>();
        events.add(new OneCardEvent.RaceResolved(race.raceId(), outcome, bySeat));
        if (outcome == RaceOutcome.CAUGHT) {
            // §9.1 — 벌칙은 차례를 끝내지 않고 공격 누적·차례 수·연속 패스 수를 바꾸지 않는다.
            int owner = race.ownerSeat();
            List<PlayingCard> drawn = drawFromPile(t, 1, events);
            List<PlayingCard> hand = t.hands.get(owner);
            hand.addAll(drawn);
            events.add(new OneCardEvent.CardsDrawn(owner, drawn.size(), DrawReason.PENALTY, hand.size(),
                    t.drawPile.size()));
            events.add(new OneCardEvent.HandUpdated(owner, hand, drawn, t.version));
        }
        passTurn(t, race.nextSeat(), events);
        return new Result(t.freeze(), events);
    }

    // ---------- 탈주 (§10, §9.2) ----------

    /**
     * 좌석 탈주 — 파산과 같은 탈락 경로. 이미 끝났거나 이미 탈락한 좌석이면 {@code NOT_APPLICABLE}(§10).
     * 경쟁 창이 열려 있으면 벌칙 없이 닫고(§9-7), 정해 둔 다음 차례로 넘기되 그 사람이 탈주자면 그다음 사람이
     * 공격 없이 받는다(§9.2). 창이 없고 탈주자의 차례였다면 걸린 공격은 사라진다(§10).
     */
    public Desertion desert(OneCardState state, int seat) {
        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
            return new Desertion(state, Desertion.Outcome.NOT_APPLICABLE, List.of());
        }
        Table t = new Table(state);
        t.version++;
        List<OneCardEvent> events = new ArrayList<>();
        RaceWindow race = t.race;
        if (race != null) {
            t.race = null;
            events.add(new OneCardEvent.RaceResolved(race.raceId(), RaceOutcome.CANCELLED, -1));
        }
        boolean deserterHadTurn = race == null && t.turnSeat == seat;
        eliminate(t, seat, Elimination.Reason.DESERTED, events);
        if (endIfDecided(t, events)) {
            return new Desertion(t.freeze(), Desertion.Outcome.MATCH_ENDED, events);
        }
        if (race != null) {
            int next = race.nextSeat();
            if (!t.alive(next)) {
                next = TurnOrder.nextAlive(t.seatCount(), t::alive, next, t.direction);
                t.attackStack = 0;
            }
            passTurn(t, next, events);
        } else if (deserterHadTurn) {
            t.attackStack = 0;
            passTurn(t, TurnOrder.nextAlive(t.seatCount(), t::alive, seat, t.direction), events);
        }
        return new Desertion(t.freeze(), Desertion.Outcome.CONTINUED, events);
    }

    // ---------- 진행 질의 ----------

    /** 지금 행동을 기다리는 좌석. 경쟁 창이 열렸거나 끝났으면 비어 있다(설계서 §4.5). */
    public List<Integer> pendingSeats(OneCardState state) {
        if (state.ended() || state.race() != null || state.turnSeat() < 0) {
            return List.of();
        }
        return List.of(state.turnSeat());
    }

    /** 그 좌석이 지금 할 수 있는 액션 전부. 7 은 지정 무늬마다 다른 액션이다. */
    public List<OneCardAction> legalActions(OneCardState state, int seat) {
        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
            return List.of();
        }
        RaceWindow race = state.race();
        if (race != null) {
            return List.of(seat == race.ownerSeat()
                    ? new OneCardAction.CallOneCard(race.raceId())
                    : new OneCardAction.Catch(race.raceId()));
        }
        if (state.turnSeat() != seat) {
            return List.of();
        }
        List<OneCardAction> actions = new ArrayList<>();
        for (PlayingCard card : state.hands().get(seat)) {
            if (!PlayRules.canPlay(state.topCard(), state.baseSuit(), state.attackStack(), card)) {
                continue;
            }
            if (card.isSuitChange()) {
                for (Suit suit : Suit.values()) {
                    actions.add(new OneCardAction.PlayCard(card, suit));
                }
            } else {
                actions.add(OneCardAction.PlayCard.of(card));
            }
        }
        actions.add(new OneCardAction.Draw());
        return List.copyOf(actions);
    }

    /** 턴 제한 초과 시의 안전 액션 — 먹기(§4). 차례가 아니면 null. */
    public OneCardAction timeoutAction(OneCardState state, int seat) {
        return pendingSeats(state).contains(seat) ? new OneCardAction.Draw() : null;
    }

    // ---------- helpers ----------

    /** §7, §7.1 — 모자라면 버린 더미의 맨 위만 남기고 섞어 채운다. 그래도 모자라면 있는 만큼만. */
    private List<PlayingCard> drawFromPile(Table t, int count, List<OneCardEvent> events) {
        List<PlayingCard> drawn = new ArrayList<>();
        while (drawn.size() < count) {
            if (t.drawPile.isEmpty()) {
                if (t.discardPile.size() <= 1) {
                    break;
                }
                PlayingCard top = t.discardPile.removeLast();
                t.drawPile.addAll(Dealer.random(rng).shuffle(t.discardPile));
                t.discardPile.clear();
                t.discardPile.add(top);
                events.add(new OneCardEvent.PileReshuffled(t.drawPile.size()));
            }
            drawn.add(t.drawPile.removeFirst());
        }
        return drawn;
    }

    /**
     * §10 — 손패를 섞지 않고 뽑을 더미 맨 아래로. 연속 패스 수는 0 으로(§11.3). 같은 전이에서 앞서 보낸 손패
     * 이벤트(먹은 직후의 손패)보다 새 버전이어야 클라가 빈 손패를 버리지 않으므로 버전을 한 번 더 올린다.
     */
    private static void eliminate(Table t, int seat, Elimination.Reason reason, List<OneCardEvent> events) {
        List<PlayingCard> hand = t.hands.get(seat);
        int held = hand.size();
        t.drawPile.addAll(hand);
        hand.clear();
        t.eliminations.add(new Elimination(seat, reason, held));
        t.passStreak = 0;
        if (t.turnSeat == seat) {
            t.turnSeat = -1;
        }
        t.version++;
        events.add(new OneCardEvent.PlayerEliminated(seat, reason, held));
        events.add(new OneCardEvent.HandUpdated(seat, List.of(), List.of(), t.version));
    }

    /** 탈락 뒤 종료 판정 — 1명만 남았으면 LAST_STANDING, 살아 있는 사람이 없으면 NO_HUMANS (§11.1). */
    private boolean endIfDecided(Table t, List<OneCardEvent> events) {
        if (t.aliveCount() == 1) {
            finish(t, EndReason.LAST_STANDING, -1, events);
            return true;
        }
        boolean humanAlive = false;
        for (int seat = 0; seat < t.seatCount(); seat++) {
            if (t.alive(seat) && !context.botSeats().contains(seat)) {
                humanAlive = true;
                break;
            }
        }
        if (!humanAlive) {
            finish(t, EndReason.NO_HUMANS, -1, events);
            return true;
        }
        return false;
    }

    private static void finish(Table t, EndReason reason, int finisher, List<OneCardEvent> events) {
        MatchResult result = new MatchResult(reason, Ranking.rank(t.hands, t.eliminations, finisher));
        t.result = result;
        t.turnSeat = -1;
        t.race = null;
        events.add(new OneCardEvent.MatchEnded(reason, result.standings()));
    }

    private static void passTurn(Table t, int seat, List<OneCardEvent> events) {
        t.turnSeat = seat;
        events.add(new OneCardEvent.TurnChanged(seat, t.direction, t.attackStack));
    }

    private static OneCardActionRejectedException rejected(RejectionReason reason) {
        return new OneCardActionRejectedException(reason);
    }

    /** 한 전이 동안만 쓰는 가변 사본. 밖으로는 {@link #freeze()} 한 레코드만 나간다. */
    private static final class Table {

        final List<List<PlayingCard>> hands = new ArrayList<>();
        final List<PlayingCard> drawPile;
        final List<PlayingCard> discardPile;
        final List<Elimination> eliminations;
        int turnSeat;
        int direction;
        Suit declaredSuit;
        int attackStack;
        RaceWindow race;
        int passStreak;
        int turnCount;
        int version;
        MatchResult result;

        Table(OneCardState s) {
            s.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
            drawPile = new ArrayList<>(s.drawPile());
            discardPile = new ArrayList<>(s.discardPile());
            eliminations = new ArrayList<>(s.eliminations());
            turnSeat = s.turnSeat();
            direction = s.direction();
            declaredSuit = s.declaredSuit();
            attackStack = s.attackStack();
            race = s.race();
            passStreak = s.passStreak();
            turnCount = s.turnCount();
            version = s.version();
            result = s.result();
        }

        int seatCount() {
            return hands.size();
        }

        boolean alive(int seat) {
            return eliminations.stream().noneMatch(e -> e.seat() == seat);
        }

        int aliveCount() {
            return seatCount() - eliminations.size();
        }

        OneCardState freeze() {
            return new OneCardState(hands, drawPile, discardPile, turnSeat, direction, declaredSuit, attackStack,
                    race, eliminations, passStreak, turnCount, version, result);
        }
    }
}
