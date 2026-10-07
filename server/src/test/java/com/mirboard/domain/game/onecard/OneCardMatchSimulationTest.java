package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardPlayed;
import com.mirboard.domain.game.onecard.event.OneCardEvent.CardsDrawn;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandDealt;
import com.mirboard.domain.game.onecard.event.OneCardEvent.HandUpdated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.MatchEnded;
import com.mirboard.domain.game.onecard.event.OneCardEvent.MatchStarted;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PileReshuffled;
import com.mirboard.domain.game.onecard.event.OneCardEvent.PlayerEliminated;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOpened;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardEvent.TurnChanged;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `docs/rules-onecard.md` §14 — 무작위 합법수로 판을 끝까지 돌린다. 경쟁 창은 무작위로 누르거나 onTimer 로 닫고,
 * 가끔 탈주를 끼워 넣는다. 매 전이 뒤에 두 가지를 본다 — 상태 불변식(54장 보존·종료 판정)과, 이벤트만 듣는
 * 클라이언트가 같은 테이블을 그려 내는지(공개·비공개 이벤트 투영이 엔진 상태와 같은지, 설계서 §4.3).
 */
class OneCardMatchSimulationTest {

    private static final int GAMES_PER_SEAT_COUNT = 2_000;
    private static final int BOT_GAMES_PER_SEAT_COUNT = 300;
    /**
     * 전이 수 안전 한도. 정상 판의 전이는 차례 ≤600 + 창 ≤ 내기 수 + 탈주 ≤6 → ≤1,206 이라 넉넉하다. 넘으면
     * 끝나지 않는 판이다.
     */
    private static final int STEP_GUARD = 3 * OneCardEngine.TURN_LIMIT;

    private record Outcome(EndReason reason, boolean hitTurnLimit) {
    }

    private static Outcome play(int seats, long seed, Set<Integer> bots) {
        Random rng = new Random(seed);
        List<Long> ids = LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
        OneCardEngine engine = new OneCardEngine(
                new GameContext("sim", ids, 0, 0, new ArrayList<>(bots)), new Random(seed * 31 + 7));
        EventMirror mirror = new EventMirror(seats);
        int step = 0;
        String transition = "startMatch";
        List<OneCardEvent> events = List.of();
        try {
            OneCardEngine.Result start = engine.startMatch();
            OneCardState state = start.newState();
            events = start.events();
            verify(engine, mirror, state, events, bots);
            long now = 0;
            for (; step < STEP_GUARD; step++) {
                if (state.ended()) {
                    return new Outcome(state.result().reason(), state.turnCount() >= OneCardEngine.TURN_LIMIT);
                }
                now += 400;
                if (rng.nextInt(400) == 0) {
                    transition = "desert";
                    List<Integer> alive = state.aliveSeats();
                    OneCardEngine.Desertion desertion = engine.desert(state, alive.get(rng.nextInt(alive.size())));
                    state = desertion.newState();
                    events = desertion.events();
                    // 살아 있는 좌석의 탈주는 언제나 받아들여지고, 끝났는지는 결과와 상태가 같은 말을 한다.
                    if (desertion.outcome() == OneCardEngine.Desertion.Outcome.NOT_APPLICABLE
                            || (desertion.outcome() == OneCardEngine.Desertion.Outcome.MATCH_ENDED) != state.ended()) {
                        throw new AssertionError("desertion outcome " + desertion.outcome()
                                + " does not agree with ended=" + state.ended());
                    }
                } else {
                    OneCardEngine.Result result;
                    if (state.race() != null) {
                        // 경쟁 창 — 3분의 1은 엔진 타이머(봇 누름 또는 만료)로 닫고, 나머지는 살아 있는 사람이 누른다.
                        List<Integer> humans = state.aliveSeats().stream()
                                .filter(seat -> !bots.contains(seat)).toList();
                        if (rng.nextInt(3) == 0) {
                            transition = "onTimer";
                            result = engine.onTimer(state).orElseThrow();
                        } else {
                            transition = "press";
                            int presser = humans.get(rng.nextInt(humans.size()));
                            result = engine.apply(state, presser, engine.legalActions(state, presser).getFirst(), now);
                        }
                    } else {
                        int seat = state.turnSeat();
                        OneCardAction action = choose(engine.legalActions(state, seat), rng);
                        transition = action instanceof OneCardAction.Draw ? "draw" : "play";
                        result = engine.apply(state, seat, action, now);
                        // 먹으면 걸린 공격은 끝난다(§6.3).
                        if (action instanceof OneCardAction.Draw && result.newState().attackStack() != 0) {
                            throw new AssertionError("an attack survived a draw: " + result.newState().attackStack());
                        }
                    }
                    state = result.newState();
                    events = result.events();
                }
                verify(engine, mirror, state, events, bots);
            }
            throw new AssertionError("match did not end within " + STEP_GUARD + " steps");
        } catch (RuntimeException | AssertionError e) {
            throw new AssertionError("%d인 seed=%d step=%d bots=%s 전이=%s 이벤트=%s — %s"
                    .formatted(seats, seed, step, bots, transition, events, e), e);
        }
    }

    /** 낼 수 있으면 열에 아홉은 무작위 카드를 낸다 — 균등 추첨이면 먹기가 너무 잦아 판이 늘어진다. */
    private static OneCardAction choose(List<OneCardAction> legal, Random rng) {
        List<OneCardAction> plays = legal.stream().filter(OneCardAction.PlayCard.class::isInstance).toList();
        if (!plays.isEmpty() && rng.nextInt(10) < 9) {
            return plays.get(rng.nextInt(plays.size()));
        }
        return new OneCardAction.Draw();
    }

    /** 전이 하나가 끝날 때마다 — 상태 불변식, 이벤트 투영, 타이머와 봇에 얽힌 약속을 본다. */
    private static void verify(OneCardEngine engine, EventMirror mirror, OneCardState state,
                               List<OneCardEvent> events, Set<Integer> bots) {
        OneCardInvariantChecker.check(state);
        mirror.apply(events);
        mirror.assertMatches(state);

        // D-131 — 클라 턴 시계 모델(onecardStore turnClock). 서버는 성공한 전이마다 턴 데드라인을 처음부터 다시 건다(기다리는
        // 좌석이 없으면 걸려 있어도 발화해 봐야 아무 일이 없다). 클라는 그 순간을 TURN_CHANGED 또는 투영 차례가 있는(≥0)
        // PLAYER_ELIMINATED 로만 알고, 카드·창·종료에서 세기를 멈춘다. 기다리는 좌석이 남는 전이가 다시 셀 이벤트를 내지
        // 않으면 서버는 다시 걸었는데 클라는 줄어든 값을 계속 센다 — 같은 좌석으로 이어지는 전이라면 위의 차례 대조는 그대로
        // 통과한다(최종 리뷰 F-client-5). 거꾸로 기다리는 좌석이 없으면 클라도 세지 않아야 한다.
        boolean waiting = !engine.pendingSeats(state).isEmpty();
        EventMirror.Clock clock = mirror.clock();
        if (waiting ? clock != EventMirror.Clock.RESTARTED : clock != EventMirror.Clock.NONE) {
            throw new AssertionError("기다리는 좌석 " + engine.pendingSeats(state) + " 인데 클라 턴 시계가 " + clock
                    + " 다(다시 셀 이벤트: TurnChanged·차례 있는 PlayerEliminated): " + events);
        }

        // 엔진 타이머는 경쟁 창이 열린 동안에만 있다(설계서 §4.5).
        if (engine.timerDeadline(state).isPresent() != (state.race() != null)) {
            throw new AssertionError("timerDeadline " + engine.timerDeadline(state) + " but race=" + state.race());
        }
        // 사람이 모두 사라지면 그 자리에서 끝난다(§11.1) — 열린 판에는 살아 있는 사람이 있고, NO_HUMANS 는 봇만 남았을 때만 나온다.
        boolean humanAlive = state.aliveSeats().stream().anyMatch(seat -> !bots.contains(seat));
        if (!state.ended() && !humanAlive) {
            throw new AssertionError("no human is alive but the match is still open");
        }
        if (state.ended() && state.result().reason() == EndReason.NO_HUMANS && humanAlive) {
            throw new AssertionError("NO_HUMANS although a human is alive");
        }
    }

    /**
     * 이벤트만 듣는 클라이언트 — 공개 이벤트로 테이블을, 비공개 이벤트로 좌석별 손패를 다시 만든다. 매 전이 뒤
     * {@link #assertMatches} 가 엔진 상태와 대조한다. 이벤트가 증감이 아니라 결과값을 싣는다는 약속(설계서 §4.3)과
     * 비공개 라우팅·손패 버전 규칙이 깨지면 여기서 어긋난다. 수백만 번 도는 곳이라 통과할 때는 비교만 하고, 메시지는
     * 어긋났을 때만 만든다.
     */
    private static final class EventMirror {

        private final int[] handCounts;
        private final List<List<PlayingCard>> privateHands = new ArrayList<>();
        private final int[] handVersions;
        /** 이번 전이에서 그 좌석이 마지막으로 받은 손패 이벤트의 버전, 못 받았으면 −1. */
        private final int[] versionInTransition;
        private final Set<Integer> eliminated = new TreeSet<>();
        private int drawPileCount;
        private PlayingCard top;
        private Suit declaredSuit;
        private int attackStack;
        private int turnSeat;
        private int direction;
        private int openRaceId = -1;
        private int openRaceOwner = -1;
        private int lastRaceId = -1;
        private MatchEnded matchEnded;
        /** 클라 턴 시계의 투영 — 세지 않음 / 이전 전이에서 다시 센 것을 이어 셈 / 이번 전이에서 다시 셈. */
        enum Clock { NONE, CARRIED, RESTARTED }

        private Clock clock = Clock.NONE;
        /** 클라 스토어의 차례 — 엔진 상태와 달리 CARD_PLAYED 가 바로 −1 로 비운다(다음 차례 이벤트까지). */
        private int clientTurnSeat = -1;

        EventMirror(int seatCount) {
            handCounts = new int[seatCount];
            handVersions = new int[seatCount];
            versionInTransition = new int[seatCount];
            for (int seat = 0; seat < seatCount; seat++) {
                privateHands.add(List.of());
            }
        }

        private static AssertionError mismatch(String what, Object projected, Object actual) {
            return new AssertionError(what + ": 이벤트로 그린 값 " + projected + " ≠ 상태 " + actual);
        }

        Clock clock() {
            return clock;
        }

        void apply(List<OneCardEvent> events) {
            Arrays.fill(versionInTransition, -1);
            if (clock == Clock.RESTARTED) {
                clock = Clock.CARRIED;
            }
            for (int i = 0; i < events.size(); i++) {
                OneCardEvent event = events.get(i);
                if (matchEnded != null) {
                    throw new AssertionError("MatchEnded 뒤에 이벤트가 더 있다: " + event);
                }
                int privateSeat = switch (event) {
                    case HandDealt dealt -> dealt.seat();
                    case HandUpdated updated -> updated.seat();
                    default -> -1;
                };
                if (event.privateSeat() != privateSeat) {
                    throw mismatch("privateSeat 라우팅 " + event, event.privateSeat(), privateSeat);
                }
                switch (event) {
                    case MatchStarted started -> {
                        Arrays.fill(handCounts, started.handSize());
                        drawPileCount = started.drawPileCount();
                        top = started.startCard();
                        turnSeat = started.firstSeat();
                        clientTurnSeat = started.firstSeat();
                        direction = 1;
                        attackStack = 0;
                        declaredSuit = null;
                    }
                    case HandDealt dealt -> receiveHand(dealt.seat(), dealt.hand(), dealt.handVersion());
                    case HandUpdated updated -> receiveHand(updated.seat(), updated.hand(), updated.handVersion());
                    case CardPlayed played -> {
                        clientTurnSeat = -1;
                        clock = Clock.NONE;
                        handCounts[played.seat()] = played.handCount();
                        top = played.card();
                        declaredSuit = played.declaredSuit();
                        attackStack = played.attackStack();
                        direction = played.direction();
                    }
                    case CardsDrawn drawn -> {
                        handCounts[drawn.seat()] = drawn.handCount();
                        drawPileCount = drawn.drawPileCount();
                    }
                    case PileReshuffled reshuffled -> drawPileCount = reshuffled.drawPileCount();
                    case TurnChanged changed -> {
                        turnSeat = changed.seat();
                        direction = changed.direction();
                        attackStack = changed.attackStack();
                        clientTurnSeat = changed.seat();
                        clock = Clock.RESTARTED;
                    }
                    case RaceOpened opened -> {
                        if (openRaceId != -1) {
                            throw new AssertionError("경쟁 창이 열린 채 또 열렸다: " + opened);
                        }
                        if (opened.raceId() <= lastRaceId) {
                            throw new AssertionError("창 번호가 늘지 않았다: " + opened + " 앞서 " + lastRaceId);
                        }
                        openRaceId = opened.raceId();
                        openRaceOwner = opened.ownerSeat();
                        lastRaceId = opened.raceId();
                        turnSeat = -1;
                        clientTurnSeat = -1;
                        clock = Clock.NONE;
                    }
                    case RaceResolved resolved -> {
                        resolveRace(resolved);
                        openRaceId = -1;
                    }
                    case PlayerEliminated out -> {
                        // 클라는 차례가 정해져 있을 때만(카드 직후·경쟁 창이 아닐 때) 탈락에서 다시 센다(onecardStore).
                        if (clientTurnSeat >= 0) {
                            clock = Clock.RESTARTED;
                        }
                        handCounts[out.seat()] = 0;
                        eliminated.add(out.seat());
                        drawPileCount = out.drawPileCount();
                    }
                    case MatchEnded end -> {
                        if (i != events.size() - 1) {
                            throw new AssertionError("MatchEnded 가 전이의 마지막이 아니다: " + events);
                        }
                        matchEnded = end;
                        turnSeat = -1;
                        clientTurnSeat = -1;
                        clock = Clock.NONE;
                    }
                }
            }
        }

        private void receiveHand(int seat, List<PlayingCard> hand, int handVersion) {
            if (handVersion <= handVersions[seat]) {
                throw new AssertionError("좌석 " + seat + " 손패 버전이 늘지 않았다: " + handVersion
                        + " 앞서 " + handVersions[seat]);
            }
            privateHands.set(seat, hand);
            handVersions[seat] = handVersion;
            versionInTransition[seat] = handVersion;
        }

        /** 누른 좌석은 결과와 맞아야 한다 — 주인만 CALLED, 살아 있는 비주인만 CAUGHT, 아무도 안 눌렀으면 −1. */
        private void resolveRace(RaceResolved resolved) {
            if (resolved.raceId() != openRaceId) {
                throw new AssertionError("열린 창 " + openRaceId + " 과 다른 창이 닫혔다: " + resolved);
            }
            int by = resolved.bySeat();
            boolean valid = switch (resolved.outcome()) {
                case CALLED -> by == openRaceOwner;
                case CAUGHT -> by >= 0 && by != openRaceOwner && !eliminated.contains(by);
                case EXPIRED, CANCELLED -> by == -1;
            };
            if (!valid) {
                throw new AssertionError("창을 닫은 좌석이 결과와 맞지 않는다(주인 " + openRaceOwner + "): " + resolved);
            }
        }

        void assertMatches(OneCardState state) {
            for (int seat = 0; seat < handCounts.length; seat++) {
                List<PlayingCard> hand = state.hands().get(seat);
                if (handCounts[seat] != hand.size()) {
                    throw mismatch("공개 손패 장수 좌석 " + seat, handCounts[seat], hand.size());
                }
                if (!privateHands.get(seat).equals(hand)) {
                    throw mismatch("비공개 손패 좌석 " + seat, privateHands.get(seat), hand);
                }
                if (versionInTransition[seat] != -1 && versionInTransition[seat] != state.version()) {
                    throw mismatch("좌석 " + seat + " 마지막 손패 이벤트의 버전", versionInTransition[seat], state.version());
                }
            }
            if (drawPileCount != state.drawPile().size()) {
                throw mismatch("뽑을 더미 장수", drawPileCount, state.drawPile().size());
            }
            if (!top.equals(state.topCard())) {
                throw mismatch("맨 위 카드", top, state.topCard());
            }
            if (!Objects.equals(declaredSuit, state.declaredSuit())) {
                throw mismatch("지정 무늬", declaredSuit, state.declaredSuit());
            }
            if ((matchEnded != null) != state.ended()) {
                throw mismatch("종료 여부", matchEnded != null, state.ended());
            }
            boolean sameEliminations = eliminated.size() == state.eliminations().size();
            for (Elimination out : state.eliminations()) {
                sameEliminations &= eliminated.contains(out.seat());
            }
            if (!sameEliminations) {
                throw mismatch("탈락 좌석", eliminated, state.eliminations());
            }
            if (matchEnded != null) {
                if (matchEnded.reason() != state.result().reason()
                        || !matchEnded.standings().equals(state.result().standings())) {
                    throw mismatch("MatchEnded", matchEnded, state.result());
                }
                // 끝난 판에 닫히지 않은 창이 남으면 안 된다 — 창을 연 전이는 반드시 RaceResolved 로 짝이 맞는다.
                if (openRaceId != -1) {
                    throw mismatch("끝난 판에 열려 있는 창 번호", openRaceId, -1);
                }
                return;
            }
            if (attackStack != state.attackStack()) {
                throw mismatch("공격 누적", attackStack, state.attackStack());
            }
            if (turnSeat != state.turnSeat()) {
                throw mismatch("차례 좌석", turnSeat, state.turnSeat());
            }
            if (direction != state.direction()) {
                throw mismatch("방향", direction, state.direction());
            }
            int stateRaceId = state.race() == null ? -1 : state.race().raceId();
            if (openRaceId != stateRaceId) {
                throw mismatch("열린 창 번호", openRaceId, stateRaceId);
            }
        }
    }

    @ParameterizedTest(name = "{0}인 × " + GAMES_PER_SEAT_COUNT + "판")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    @Timeout(60)
    void every_match_ends_with_the_cards_conserved(int seats) {
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        int turnLimitHits = 0;
        for (int game = 0; game < GAMES_PER_SEAT_COUNT; game++) {
            Outcome outcome = play(seats, seats * 1_000_003L + game, Set.of());
            reasons.merge(outcome.reason(), 1, Integer::sum);
            if (outcome.hitTurnLimit()) {
                turnLimitHits++;
            }
        }

        // 무작위 판도 상당수는 다 내서 끝나고(실측 최저 2인 48%), 탈주·파산으로 한 명만 남아 끝나는 판도 있다.
        assertThat(reasons.getOrDefault(EndReason.FINISHED, 0)).as("%s", reasons)
                .isGreaterThanOrEqualTo(GAMES_PER_SEAT_COUNT / 4);
        assertThat(reasons).as("%s", reasons).containsKey(EndReason.LAST_STANDING);
        // 차례 상한은 안전장치다 — 전략 없는 무작위 판에서도 열에 아홉은 그 전에 끝나야 한다(§11.3, §14).
        // 측정값(시드 고정): 2인 1% 미만 · 3인 1.0% · 4인 2.1% · 5인 2.3% · 6인 5.1%.
        assertThat(turnLimitHits).as("차례 상한 도달 %d판 / %s", turnLimitHits, reasons)
                .isLessThan(GAMES_PER_SEAT_COUNT / 10);
    }

    /** 봇 좌석은 경쟁 창에서 엔진 타이머({@code onTimer})의 봇 누름으로만 참여한다. */
    @ParameterizedTest(name = "{0}인(사람 1 + 봇) × " + BOT_GAMES_PER_SEAT_COUNT + "판")
    @ValueSource(ints = {2, 3, 4, 5, 6})
    @Timeout(60)
    void matches_with_one_human_and_bots_always_end(int seats) {
        Set<Integer> bots = IntStream.range(1, seats).boxed().collect(Collectors.toSet());
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        for (int game = 0; game < BOT_GAMES_PER_SEAT_COUNT; game++) {
            reasons.merge(play(seats, seats * 7_919L + game, bots).reason(), 1, Integer::sum);
        }

        if (seats >= 3) {
            // 사람이 탈주·파산해 봇만 남으면 그 자리에서 끝난다(§11.1 NO_HUMANS).
            assertThat(reasons).as("%s", reasons).containsKey(EndReason.NO_HUMANS);
        } else {
            // 2인은 사람이 빠지면 봇 1명만 남아 LAST_STANDING 이 먼저 판정된다.
            assertThat(reasons).as("%s", reasons).doesNotContainKey(EndReason.NO_HUMANS);
        }
    }
}
