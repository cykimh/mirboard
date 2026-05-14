package com.mirboard.domain.game.tichu;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.tichu.action.ActionValidator;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.card.Wish;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.scoring.ScoreCalculator;
import com.mirboard.domain.game.tichu.state.PassCardsSelection;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 티츄 라운드의 상태 전이를 담당하는 엔진. 외부에서는 {@link #apply(TichuState, int,
 * TichuAction)} 만 호출하고, 엔진은 검증 후 새 상태 + 발행할 이벤트 목록을 반환한다.
 *
 * <p>본 클래스는 의도적으로 stateless — `context` 만 보유하고 모든 호출은 입력
 * 상태에서 출력 상태로의 순수 함수. 동시성 직렬화는 컨트롤러/락이 담당.
 */
public final class TichuEngine implements GameEngine {

    private final GameContext context;

    public TichuEngine(GameContext context) {
        this.context = context;
    }

    public GameContext context() {
        return context;
    }

    public Result apply(TichuState state, int seat, TichuAction action) {
        ActionValidator.validate(state, seat, action);

        return switch (action) {
            case TichuAction.PlayCard pc -> applyPlayCard(state, seat, pc);
            case TichuAction.PassTrick __ -> applyPassTrick(state, seat);
            case TichuAction.DeclareTichu __ -> applyDeclareTichu(state, seat);
            case TichuAction.DeclareGrandTichu __ -> applyDeclareGrandTichu(state, seat);
            case TichuAction.Ready __ -> applyReady(state, seat);
            case TichuAction.PassCards pc -> applyPassCards(state, seat, pc);
            case TichuAction.MakeWish w -> applyMakeWish(state, seat, w);
            case TichuAction.GiveDragonTrick g -> applyGiveDragonTrick(state, seat, g);
        };
    }

    // ---------- Dealing / Passing lifecycle ----------

    private Result applyDeclareGrandTichu(TichuState state, int seat) {
        // Validator 가 Dealing(8) 인지 확인. 선언 + ready 둘 다 표시 후 4명 ready 시 다음 단계.
        TichuState.Dealing dealing = (TichuState.Dealing) state;
        List<PlayerState> players = new ArrayList<>(dealing.players());
        players.set(seat, players.get(seat).withDeclaration(TichuDeclaration.GRAND_TICHU));
        Set<Integer> ready = new HashSet<>(dealing.ready());
        ready.add(seat);

        List<TichuEvent> events = new ArrayList<>();
        events.add(new TichuEvent.TichuDeclared(seat, TichuDeclaration.GRAND_TICHU));
        events.add(new TichuEvent.PlayerReady(seat));

        return advanceFromDealing(
                new TichuState.Dealing(players, dealing.phaseCardCount(), ready,
                        dealing.reservedSecondHalf()),
                events);
    }

    private Result applyDeclareTichu(TichuState state, int seat) {
        // Dealing(14) 또는 Playing 둘 다 허용.
        if (state instanceof TichuState.Dealing dealing) {
            List<PlayerState> players = new ArrayList<>(dealing.players());
            players.set(seat, players.get(seat).withDeclaration(TichuDeclaration.TICHU));
            Set<Integer> ready = new HashSet<>(dealing.ready());
            ready.add(seat);

            List<TichuEvent> events = new ArrayList<>();
            events.add(new TichuEvent.TichuDeclared(seat, TichuDeclaration.TICHU));
            events.add(new TichuEvent.PlayerReady(seat));
            return advanceFromDealing(
                    new TichuState.Dealing(players, dealing.phaseCardCount(), ready,
                            dealing.reservedSecondHalf()),
                    events);
        }
        // Playing: 첫 플레이 전까지 (handSize==14) 만 허용 — Validator 가 확인.
        TichuState.Playing playing = (TichuState.Playing) state;
        List<PlayerState> players = new ArrayList<>(playing.players());
        players.set(seat, players.get(seat).withDeclaration(TichuDeclaration.TICHU));
        TichuState newState = new TichuState.Playing(players, playing.trick(), playing.firstFinisher());
        return new Result(newState, List.of(new TichuEvent.TichuDeclared(seat, TichuDeclaration.TICHU)));
    }

    private Result applyReady(TichuState state, int seat) {
        TichuState.Dealing dealing = (TichuState.Dealing) state;
        Set<Integer> ready = new HashSet<>(dealing.ready());
        ready.add(seat);

        List<TichuEvent> events = new ArrayList<>();
        events.add(new TichuEvent.PlayerReady(seat));

        return advanceFromDealing(
                new TichuState.Dealing(dealing.players(), dealing.phaseCardCount(), ready,
                        dealing.reservedSecondHalf()),
                events);
    }

    private Result advanceFromDealing(TichuState.Dealing dealing, List<TichuEvent> events) {
        if (dealing.ready().size() < TurnManager.SEATS) {
            return new Result(dealing, events);
        }
        // All 4 ready in this phase.
        if (dealing.phaseCardCount() == 8) {
            // 14장 단계로 전이 — 각자 reservedSecondHalf 를 hand 에 합친다.
            List<PlayerState> updatedPlayers = new ArrayList<>(TurnManager.SEATS);
            for (PlayerState p : dealing.players()) {
                List<Card> reserved = dealing.reservedSecondHalf()
                        .getOrDefault(p.seat(), List.of());
                List<Card> merged = new ArrayList<>(p.hand());
                merged.addAll(reserved);
                updatedPlayers.add(p.withHand(merged));
            }
            TichuState.Dealing next = new TichuState.Dealing(
                    updatedPlayers, 14, Set.of(), Map.of());
            events.add(new TichuEvent.DealingPhaseStarted(14));
            for (PlayerState p : updatedPlayers) {
                events.add(new TichuEvent.HandDealt(p.seat(), p.hand(), 14));
            }
            return new Result(next, events);
        }
        // phaseCardCount==14 → Passing 단계.
        TichuState.Passing passing = new TichuState.Passing(dealing.players(), Map.of());
        events.add(new TichuEvent.PassingStarted());
        return new Result(passing, events);
    }

    private Result applyPassCards(TichuState state, int seat, TichuAction.PassCards action) {
        TichuState.Passing passing = (TichuState.Passing) state;
        Map<Integer, PassCardsSelection> submitted = new LinkedHashMap<>(passing.submitted());
        submitted.put(seat, new PassCardsSelection(
                action.toLeft(), action.toPartner(), action.toRight()));

        List<TichuEvent> events = new ArrayList<>();
        events.add(new TichuEvent.PassingSubmitted(seat));

        if (submitted.size() < TurnManager.SEATS) {
            return new Result(new TichuState.Passing(passing.players(), submitted), events);
        }
        // 4명 모두 제출 — 동시 스왑 후 Playing 으로 전이.
        return swapAndStartPlaying(passing.players(), submitted, events);
    }

    private Result swapAndStartPlaying(List<PlayerState> currentPlayers,
                                       Map<Integer, PassCardsSelection> submitted,
                                       List<TichuEvent> events) {
        List<PlayerState> updatedPlayers = new ArrayList<>(TurnManager.SEATS);
        for (int r = 0; r < TurnManager.SEATS; r++) {
            PlayerState p = currentPlayers.get(r);
            PassCardsSelection mine = submitted.get(r);
            List<Card> newHand = new ArrayList<>(p.hand());
            // 보낸 3장 제거.
            newHand.remove(mine.toLeft());
            newHand.remove(mine.toPartner());
            newHand.remove(mine.toRight());
            // 받은 3장 추가: (R+3)%4 의 toLeft, (R+2)%4 의 toPartner, (R+1)%4 의 toRight.
            newHand.add(submitted.get((r + 3) % TurnManager.SEATS).toLeft());
            newHand.add(submitted.get((r + 2) % TurnManager.SEATS).toPartner());
            newHand.add(submitted.get((r + 1) % TurnManager.SEATS).toRight());
            updatedPlayers.add(p.withHand(newHand));
        }
        events.add(new TichuEvent.CardsPassed());
        for (PlayerState p : updatedPlayers) {
            events.add(new TichuEvent.HandDealt(p.seat(), p.hand(), 14));
        }
        int leadSeat = mahjongHolder(updatedPlayers);
        TichuState.Playing playing = new TichuState.Playing(
                updatedPlayers, TrickState.lead(leadSeat, null), -1);
        events.add(new TichuEvent.PlayingStarted(leadSeat));
        events.add(new TichuEvent.TurnChanged(leadSeat));
        return new Result(playing, events);
    }

    private static int mahjongHolder(List<PlayerState> players) {
        for (PlayerState p : players) {
            if (p.hand().stream().anyMatch(c -> c.is(Special.MAHJONG))) {
                return p.seat();
            }
        }
        throw new IllegalStateException("Mahjong not found in any post-swap hand");
    }

    // ---------- PlayCard ----------

    private Result applyPlayCard(TichuState state, int seat, TichuAction.PlayCard action) {
        TichuState.Playing playing = (TichuState.Playing) state;
        TrickState trick = playing.trick();

        Hand detected = HandDetector.detect(action.cards()).orElseThrow();
        Hand normalized = normalizePhoenix(detected, trick.currentTop());

        List<PlayerState> players = new ArrayList<>(playing.players());
        PlayerState player = players.get(seat);
        List<Card> newHand = new ArrayList<>(player.hand());
        for (Card c : action.cards()) {
            newHand.remove(c);
        }
        player = player.withHand(newHand);

        List<TichuEvent> events = new ArrayList<>();
        events.add(new TichuEvent.Played(seat, normalized));

        if (newHand.isEmpty()) {
            int order = 1 + (int) players.stream().filter(PlayerState::isFinished).count();
            player = player.withFinishedOrder(order);
            events.add(new TichuEvent.PlayerFinished(seat, order));
        }
        players.set(seat, player);

        // Dog: trick immediately resolves, partner leads with no point transfer.
        boolean isDog = action.cards().size() == 1 && action.cards().get(0).is(Special.DOG);
        if (isDog) {
            int partner = TurnManager.partnerOf(seat);
            TrickState next = TrickState.lead(partner, trick.activeWish());
            events.add(new TichuEvent.TrickTaken(partner, 0));
            events.add(new TichuEvent.TurnChanged(partner));
            return maybeEndRound(
                    new TichuState.Playing(players, next, firstFinisher(players)),
                    events);
        }

        // Wish fulfillment (engine-level — validator's conservative check ensures the
        // wished rank is present if held; here we just flag the wish as fulfilled when
        // the wished rank appears in the play).
        Wish wishBefore = trick.activeWish();
        Wish updatedWish = wishBefore;
        if (wishBefore != null && wishBefore.isActive()
                && action.cards().stream().anyMatch(c -> c.isNormal() && c.rank() == wishBefore.rank())) {
            updatedWish = wishBefore.fulfill();
        }

        List<Card> newAccumulated = new ArrayList<>(trick.accumulatedCards());
        newAccumulated.addAll(action.cards());
        List<Hand> newPlaySequence = new ArrayList<>(trick.playSequence());
        newPlaySequence.add(normalized);

        TrickState pendingTrick = new TrickState(
                trick.leadSeat(),
                trick.currentTurnSeat(),  // recomputed below
                normalized,
                seat,
                trick.passedSeats(),
                newPlaySequence,
                newAccumulated,
                updatedWish);

        // Round end via double victory or 3 finished — check before advancing turn.
        TichuState.Playing afterPlay = new TichuState.Playing(
                players, pendingTrick, firstFinisher(players));
        if (shouldEndRound(players)) {
            return endRoundClosingTrick(afterPlay, events);
        }

        int nextSeat = TurnManager.advanceTurn(afterPlay);
        if (nextSeat == seat) {
            // Everyone else has passed/finished — trick closes immediately.
            return closeTrickAndContinue(afterPlay, events);
        }

        TrickState finalTrick = withTurn(pendingTrick, nextSeat);
        events.add(new TichuEvent.TurnChanged(nextSeat));
        return new Result(
                new TichuState.Playing(players, finalTrick, firstFinisher(players)),
                events);
    }

    // ---------- PassTrick ----------

    private Result applyPassTrick(TichuState state, int seat) {
        TichuState.Playing playing = (TichuState.Playing) state;
        TrickState trick = playing.trick();

        List<TichuEvent> events = new ArrayList<>();
        events.add(new TichuEvent.Passed(seat));

        var passed = new HashSet<>(trick.passedSeats());
        passed.add(seat);

        TrickState pending = new TrickState(
                trick.leadSeat(),
                trick.currentTurnSeat(),
                trick.currentTop(),
                trick.currentTopSeat(),
                passed,
                trick.playSequence(),
                trick.accumulatedCards(),
                trick.activeWish());

        TichuState.Playing afterPass = new TichuState.Playing(
                playing.players(), pending, playing.firstFinisher());

        int nextSeat = TurnManager.advanceTurn(afterPass);
        if (nextSeat == trick.currentTopSeat()) {
            // Trick closes — winner takes accumulated cards.
            return closeTrickAndContinue(afterPass, events);
        }

        TrickState finalTrick = withTurn(pending, nextSeat);
        events.add(new TichuEvent.TurnChanged(nextSeat));
        return new Result(
                new TichuState.Playing(playing.players(), finalTrick, playing.firstFinisher()),
                events);
    }

    // ---------- Wish ----------

    private Result applyMakeWish(TichuState state, int seat, TichuAction.MakeWish action) {
        TichuState.Playing playing = (TichuState.Playing) state;
        TrickState trick = playing.trick();
        TrickState updated = new TrickState(
                trick.leadSeat(), trick.currentTurnSeat(), trick.currentTop(),
                trick.currentTopSeat(), trick.passedSeats(), trick.playSequence(),
                trick.accumulatedCards(), Wish.active(action.rank()));
        return new Result(
                new TichuState.Playing(playing.players(), updated, playing.firstFinisher()),
                List.of(new TichuEvent.WishMade(action.rank())));
    }

    // ---------- Dragon Give ----------

    private Result applyGiveDragonTrick(TichuState state, int seat, TichuAction.GiveDragonTrick action) {
        TichuState.Playing playing = (TichuState.Playing) state;
        TrickState trick = playing.trick();

        List<PlayerState> players = new ArrayList<>(playing.players());
        PlayerState recipient = players.get(action.toSeat());
        List<Card> newTricksWon = new ArrayList<>(recipient.tricksWon());
        newTricksWon.addAll(trick.accumulatedCards());
        players.set(action.toSeat(),
                new PlayerState(recipient.seat(), recipient.hand(),
                        recipient.declaration(), recipient.finishedOrder(), newTricksWon));

        // Next trick: Dragon player still leads.
        int nextLead = playing.players().get(seat).isFinished()
                ? nextActiveSeat(players, seat)
                : seat;
        TrickState nextTrick = TrickState.lead(nextLead, null);

        List<TichuEvent> events = new ArrayList<>();
        events.add(new TichuEvent.DragonGiven(seat, action.toSeat()));
        events.add(new TichuEvent.TrickTaken(action.toSeat(),
                com.mirboard.domain.game.tichu.scoring.CardPoints.sum(trick.accumulatedCards())));
        events.add(new TichuEvent.TurnChanged(nextLead));

        return maybeEndRound(
                new TichuState.Playing(players, nextTrick, firstFinisher(players)),
                events);
    }

    // ---------- Trick closure / round end ----------

    private Result closeTrickAndContinue(TichuState.Playing afterAction, List<TichuEvent> events) {
        TrickState trick = afterAction.trick();
        List<PlayerState> players = new ArrayList<>(afterAction.players());
        int taker = trick.currentTopSeat();
        PlayerState takerPlayer = players.get(taker);

        // Dragon trick must be given before next trick — engine emits TrickTaken later via
        // GiveDragonTrick. Here we still pass the accumulated to the taker unless Dragon won.
        boolean dragonWon = trick.currentTop() != null
                && trick.currentTop().cards().size() == 1
                && trick.currentTop().cards().get(0).is(Special.DRAGON);
        if (dragonWon) {
            // Pending — keep the accumulated on the trick state; the next call must be
            // GiveDragonTrick from `taker`. Re-emit a stub TrickTaken once given.
            events.add(new TichuEvent.TurnChanged(taker));
            TrickState pending = new TrickState(
                    trick.leadSeat(), taker, trick.currentTop(), taker,
                    trick.passedSeats(), trick.playSequence(),
                    trick.accumulatedCards(), trick.activeWish());
            return new Result(
                    new TichuState.Playing(players, pending, afterAction.firstFinisher()),
                    events);
        }

        // Normal: taker collects.
        List<Card> newTricksWon = new ArrayList<>(takerPlayer.tricksWon());
        newTricksWon.addAll(trick.accumulatedCards());
        players.set(taker, new PlayerState(takerPlayer.seat(), takerPlayer.hand(),
                takerPlayer.declaration(), takerPlayer.finishedOrder(), newTricksWon));

        events.add(new TichuEvent.TrickTaken(taker,
                com.mirboard.domain.game.tichu.scoring.CardPoints.sum(trick.accumulatedCards())));

        if (shouldEndRound(players)) {
            return endRoundWith(players, events);
        }

        int newLead = takerPlayer.isFinished()
                ? nextActiveSeat(players, taker)
                : taker;
        TrickState nextTrick = TrickState.lead(newLead, trick.activeWish());
        events.add(new TichuEvent.TurnChanged(newLead));

        return new Result(
                new TichuState.Playing(players, nextTrick, firstFinisher(players)),
                events);
    }

    private Result endRoundClosingTrick(TichuState.Playing state, List<TichuEvent> events) {
        // Close any in-progress trick — currentTopSeat takes accumulated.
        TrickState trick = state.trick();
        List<PlayerState> players = new ArrayList<>(state.players());
        if (trick.currentTopSeat() >= 0 && !trick.accumulatedCards().isEmpty()) {
            int taker = trick.currentTopSeat();
            PlayerState p = players.get(taker);
            List<Card> tricks = new ArrayList<>(p.tricksWon());
            tricks.addAll(trick.accumulatedCards());
            players.set(taker, new PlayerState(
                    p.seat(), p.hand(), p.declaration(), p.finishedOrder(), tricks));
            events.add(new TichuEvent.TrickTaken(taker,
                    com.mirboard.domain.game.tichu.scoring.CardPoints.sum(trick.accumulatedCards())));
        }
        return endRoundWith(players, events);
    }

    private Result endRoundWith(List<PlayerState> players, List<TichuEvent> events) {
        RoundScore score = ScoreCalculator.compute(players);
        events.add(new TichuEvent.RoundEnded(score));
        TichuState newState = new TichuState.RoundEnd(
                players, score.teamAScore(), score.teamBScore());
        return new Result(newState, events);
    }

    private Result maybeEndRound(TichuState.Playing afterAction, List<TichuEvent> events) {
        if (shouldEndRound(afterAction.players())) {
            return endRoundClosingTrick(afterAction, events);
        }
        return new Result(afterAction, events);
    }

    private static boolean shouldEndRound(List<PlayerState> players) {
        long finishedCount = players.stream().filter(PlayerState::isFinished).count();
        if (finishedCount >= 3) return true;
        // Double victory: top 2 finishers same team.
        if (finishedCount == 2) {
            var top2 = players.stream()
                    .filter(PlayerState::isFinished)
                    .toList();
            return Team.ofSeat(top2.get(0).seat()) == Team.ofSeat(top2.get(1).seat());
        }
        return false;
    }

    private static int nextActiveSeat(List<PlayerState> players, int from) {
        int candidate = TurnManager.nextSeat(from);
        for (int i = 0; i < TurnManager.SEATS; i++) {
            if (!players.get(candidate).isFinished()) return candidate;
            candidate = TurnManager.nextSeat(candidate);
        }
        return from;
    }

    private static int firstFinisher(List<PlayerState> players) {
        return players.stream()
                .filter(p -> p.finishedOrder() == 1)
                .mapToInt(PlayerState::seat)
                .findFirst()
                .orElse(-1);
    }

    private static TrickState withTurn(TrickState t, int newTurnSeat) {
        return new TrickState(
                t.leadSeat(), newTurnSeat, t.currentTop(), t.currentTopSeat(),
                t.passedSeats(), t.playSequence(), t.accumulatedCards(), t.activeWish());
    }

    /**
     * Phoenix 단독 SINGLE 의 placeholder rank 를 트릭 컨텍스트에서 정규화한다.
     * 리드면 1 (Mahjong 바로 위), 그렇지 않으면 currentTop.rank — 후속 비교가 정수
     * 산수 ({@code challenger.rank > current.rank}) 로 일관되도록.
     */
    private static Hand normalizePhoenix(Hand detected, Hand currentTop) {
        if (!detected.phoenixSingle()) return detected;
        int effectiveRank = (currentTop == null) ? 1 : currentTop.rank();
        return new Hand(detected.type(), detected.cards(), effectiveRank, 1, false);
    }

    public record Result(TichuState newState, List<TichuEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }
}
