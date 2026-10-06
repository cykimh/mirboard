package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.onecard.OneCardGameEngine;
import com.mirboard.domain.game.onecard.RaceSettings;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.metrics.MirboardMetrics;
import com.mirboard.infra.scheduling.DeadlineHandler;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.GameStompController;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * S5 — 진행 킥의 끝-끝 시나리오. 리뷰가 재현한 세 정지(C-I1·C-I2)를 실제 부품으로 만들고, 킥 한 번(재접속이면 몇 번)이 판을
 * 다시 움직이는지 본다.
 *
 * <p>실제 {@link OneCardGameEngine}·{@link GameStompController}·{@link TurnTimeoutScheduler}·{@link EngineTimerScheduler}·
 * {@link BotScheduler}(지연 0)·{@link MatchProgressService}·{@link GameProgressKick} 을 쓰고, Redis 에 기대는 부품(상태 저장소·
 * 데드라인 큐·세대·방 락)만 메모리 가짜로 바꾼다. 폴러는 가짜 시계로 손으로 돌린다. Docker 불필요.
 */
class GameProgressKickScenarioTest {

    static final class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(1_700_000_000_000L);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }

        @Override
        public long millis() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    private static final String ROOM = "r1";
    private static final PlayingCard HEART_9 = PlayingCard.of(Suit.HEART, 9);

    final MutableClock clock = new MutableClock();
    final AtomicReference<OneCardState> stored = new AtomicReference<>();
    final Map<String, Map<String, Long>> queue = new ConcurrentHashMap<>();
    final AtomicLong gen = new AtomicLong();
    final AtomicBoolean locked = new AtomicBoolean();
    final AtomicBoolean failNextGameArm = new AtomicBoolean();
    final AtomicBoolean failNextBroadcast = new AtomicBoolean();
    final List<List<? extends GameEvent>> broadcasts = new CopyOnWriteArrayList<>();

    final OneCardStateStore store = mock(OneCardStateStore.class);
    final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    final RoomGeneration generations = mock(RoomGeneration.class);
    final RoomActionLock lock = mock(RoomActionLock.class);
    final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    final RoomService roomService = mock(RoomService.class);
    final GameEngineProvider engines = mock(GameEngineProvider.class);
    final GameRegistry games = mock(GameRegistry.class);
    final MirboardMetrics metrics = mock(MirboardMetrics.class);
    final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    final BotScheduler botProxy = mock(BotScheduler.class);

    TurnTimeoutScheduler turnTimeout;
    EngineTimerScheduler engineTimer;
    GameStompController controller;
    BotScheduler bots;
    GameProgressKick kick;
    Room room;

    @SuppressWarnings("unchecked")
    void wire(List<Long> players, List<Integer> botSeats, int turnSeconds) {
        room = new Room(ROOM, "방", "ONE_CARD", players.get(0), RoomStatus.IN_GAME, players.size(), players.size(),
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, !botSeats.isEmpty(), botSeats, 1000, turnSeconds, 0,
                Set.of());
        when(roomService.getRoom(ROOM)).thenAnswer(i -> room);
        GameContext ctx = new GameContext(ROOM, players, 1000, 0, botSeats);
        when(engines.forRoom(any())).thenAnswer(i ->
                new OneCardGameEngine(ctx, store, clock, new Random(7), RaceSettings.DEFAULT, publisher));
        GameDefinition def = mock(GameDefinition.class);
        when(def.supportsRematch()).thenReturn(false);
        when(games.require(anyString())).thenReturn(def);

        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());

        // 데드라인 큐 — 같은 member 는 하나뿐(ZSET), scheduleIfAbsent 는 없을 때만(ZADD NX).
        doAnswer(i -> {
            String kind = i.getArgument(0);
            if (kind.equals(EngineTimerScheduler.KIND) && failNextGameArm.getAndSet(false)) {
                throw new RedisConnectionFailureException("simulated blip on ZADD deadlines:game");
            }
            queue.computeIfAbsent(kind, k -> new ConcurrentHashMap<>())
                    .put(i.getArgument(1), clock.millis() + Math.max(0, ((Duration) i.getArgument(2)).toMillis()));
            return null;
        }).when(deadlines).schedule(anyString(), anyString(), any());
        when(deadlines.scheduleIfAbsent(anyString(), anyString(), any())).thenAnswer(i ->
                queue.computeIfAbsent((String) i.getArgument(0), k -> new ConcurrentHashMap<>())
                        .putIfAbsent(i.getArgument(1),
                                clock.millis() + Math.max(0, ((Duration) i.getArgument(2)).toMillis())) == null);
        doAnswer(i -> {
            queue.getOrDefault((String) i.getArgument(0), new ConcurrentHashMap<>()).remove((String) i.getArgument(1));
            return null;
        }).when(deadlines).cancel(anyString(), anyString());

        when(generations.current(ROOM)).thenAnswer(i -> gen.get());
        when(generations.bump(ROOM)).thenAnswer(i -> gen.incrementAndGet());

        when(lock.tryAcquire(ROOM)).thenAnswer(i -> locked.compareAndSet(false, true));
        when(lock.acquireWaiting(ROOM)).thenAnswer(i -> locked.compareAndSet(false, true));
        doAnswer(i -> {
            locked.set(false);
            return null;
        }).when(lock).release(ROOM);

        doAnswer(i -> {
            if (failNextBroadcast.getAndSet(false)) {
                throw new RedisConnectionFailureException("simulated blip on PUBLISH");
            }
            broadcasts.add(List.copyOf((List<? extends GameEvent>) i.getArgument(1)));
            return null;
        }).when(broadcaster).broadcast(anyString(), anyList(), anyList());

        MatchProgressService matchProgress = new MatchProgressService(roomService, metrics, games);
        turnTimeout = new TurnTimeoutScheduler(roomService, engines, broadcaster, lock, matchProgress, botProxy,
                deadlines, generations);
        bots = new BotScheduler(roomService, engines, broadcaster, lock, matchProgress,
                mock(BotUserRegistry.class), turnTimeout, 1L, 0L);
        doAnswer(i -> {
            bots.scheduleBots(i.getArgument(0));
            return null;
        }).when(botProxy).scheduleBots(anyString());
        engineTimer = new EngineTimerScheduler(roomService, engines, broadcaster, lock, matchProgress, botProxy,
                turnTimeout, deadlines, generations);
        kick = new GameProgressKick(roomService, engines, bots, deadlines, generations, Runnable::run);
        controller = new GameStompController(roomService, engines, new ObjectMapper(), broadcaster, lock,
                matchProgress, botProxy, turnTimeout, metrics);
    }

    /** 좌석 0 이 ♥9·♣3 으로 차례. 나머지 좌석은 3장씩. */
    static OneCardState aboutToGoDownToOne(int seatCount) {
        List<List<PlayingCard>> hands = new ArrayList<>();
        hands.add(List.of(HEART_9, PlayingCard.of(Suit.CLUB, 3)));
        Suit[] suits = {Suit.SPADE, Suit.DIAMOND};
        for (int seat = 1; seat < seatCount; seat++) {
            Suit suit = suits[seat - 1];
            hands.add(List.of(PlayingCard.of(suit, 4), PlayingCard.of(suit, 6), PlayingCard.of(suit, 8)));
        }
        PlayingCard top = PlayingCard.of(Suit.HEART, 5);
        List<PlayingCard> used = new ArrayList<>();
        hands.forEach(used::addAll);
        used.add(top);
        List<PlayingCard> drawPile = Deck.all().stream().filter(card -> !used.contains(card)).toList();
        return new OneCardState(hands, drawPile, List.of(top), 0, 1, null, 0, null, List.of(), 0, 0, 1, null);
    }

    /** DeadlinePoller.pollOnce 흉내 — 가짜 시계로 만기분을 원자 pop 해 핸들러에 넘긴다. */
    void pollDue() {
        for (String kind : List.of(TurnTimeoutScheduler.KIND, EngineTimerScheduler.KIND)) {
            Map<String, Long> m = queue.getOrDefault(kind, new ConcurrentHashMap<>());
            List<String> due = m.entrySet().stream().filter(e -> e.getValue() <= clock.millis())
                    .map(Map.Entry::getKey).toList();
            due.forEach(m::remove);
            DeadlineHandler handler = kind.equals(TurnTimeoutScheduler.KIND) ? turnTimeout : engineTimer;
            for (String member : due) {
                try {
                    handler.handle(member);
                } catch (RuntimeException e) {
                    // DeadlinePoller 처럼 삼킨다.
                }
            }
        }
    }

    int armedCount() {
        return queue.values().stream().mapToInt(Map::size).sum();
    }

    void play(long userId, Map<String, Object> action) {
        controller.onAction(ROOM, action, new AuthPrincipal(userId, "u" + userId));
    }

    static Map<String, Object> playHeartNine() {
        return Map.of("@action", "PLAY_CARD", "card", Map.of("suit", "HEART", "rank", 9));
    }

    static void settle() throws InterruptedException {
        Thread.sleep(300); // 봇 루프(가상 스레드, 지연 0)가 돌 시간
    }

    /**
     * C-I2 경로 1 — 창을 연 직후 엔진 타이머 무장(ZADD)이 실패했다. 사람 둘이라 정상이면 3초 뒤 EXPIRED 인데, 10분이 지나도 창이
     * 열려 있었다(그 뒤의 '잡기!'가 벌칙을 줬다). 클라의 마감 + 1.5초 resync 가 건 킥이 타이머를 다시 걸어 다음 폴링에 만료로 닫는다.
     */
    @Test
    void a_kick_after_a_lost_arm_closes_the_race_as_expired_without_a_penalty() throws Exception {
        wire(List.of(10L, 30L), List.of(), 30);
        stored.set(aboutToGoDownToOne(2));
        failNextGameArm.set(true);

        play(10L, playHeartNine());
        settle();
        assertThat(stored.get().race()).as("창이 열렸다").isNotNull();
        assertThat(queue.getOrDefault(EngineTimerScheduler.KIND, Map.of())).as("엔진 타이머 없음").isEmpty();

        clock.advance(10 * 60_000);
        pollDue();
        settle();
        assertThat(stored.get().race()).as("10분 — 창이 그대로").isNotNull();

        kick.kickNow(ROOM, 30L);
        assertThat(queue.get(EngineTimerScheduler.KIND)).as("현 세대로 다시 걸렸다").containsKey(ROOM + "#" + gen.get());
        pollDue();
        settle();

        OneCardState after = stored.get();
        assertThat(after.race()).as("창이 닫혔다").isNull();
        assertThat(after.hands().get(0)).as("만료 — 벌칙 없음").hasSize(1);
        assertThat(broadcasts.getLast()).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(OneCardEvent.RaceResolved.class, resolved ->
                        assertThat(resolved.outcome()).isEqualTo(OneCardEvent.RaceOutcome.EXPIRED)));
    }

    /**
     * C-I2 경로 3 — 봇의 '잡기!' 발화가 창을 닫아 저장한 뒤 방송이 실패했다. 차례는 봇인데 턴 제한이 꺼져 걸린 데드라인이 0 개라
     * 아무도 봇을 깨우지 않았다. 킥 한 번에 봇이 둔다.
     */
    @Test
    void a_kick_after_a_fire_failure_resumes_the_bot_turn() throws Exception {
        wire(List.of(10L, 20L, 30L), List.of(1), 0);
        stored.set(aboutToGoDownToOne(3));
        play(10L, playHeartNine());
        settle();
        clock.advance(2_600); // 봇 반응(1.0~2.5초) 지남
        failNextBroadcast.set(true);
        pollDue();
        settle();
        OneCardState stuck = stored.get();
        assertThat(stuck.race()).as("창은 닫혀 저장됐다").isNull();
        assertThat(stuck.turnSeat()).as("차례는 봇").isEqualTo(1);
        assertThat(armedCount()).as("걸린 데드라인 0").isZero();

        kick.kickNow(ROOM, 10L);
        settle();

        assertThat(stored.get().version()).as("킥 한 번으로 봇이 둔다").isGreaterThan(stuck.version());
    }

    /**
     * C-I1 — 재기동(배포·Fly 자동 정지)으로 메모리의 봇 루프가 사라졌다. Redis 에는 봇(좌석 1) 차례로 저장된 상태와 세대만 남고
     * 턴 제한이 꺼져 데드라인도 없다. 다시 붙는 클라들의 킥(마운트 resync·구독·접속 resync — 세 번)이 봇 차례를 이어 준다.
     * (킥이 루프를 겹쳐 걸지 않는지는 {@link BotSchedulerTest} 가 본다 — 여기서는 봇이 하나·지연 0 이라 겹침이 보이지 않는다.)
     */
    @Test
    void after_a_restart_the_reconnecting_clients_kicks_resume_the_bot_turn() throws Exception {
        wire(List.of(10L, 20L, 30L), List.of(1), 0);
        OneCardState base = aboutToGoDownToOne(3);
        stored.set(new OneCardState(base.hands(), base.drawPile(), base.discardPile(), 1, 1, null, 0, null,
                List.of(), 0, 0, 1, null));
        gen.set(5);
        int before = stored.get().version();

        kick.kickNow(ROOM, 10L);
        kick.kickNow(ROOM, 10L);
        kick.kickNow(ROOM, 30L);
        settle();

        assertThat(stored.get().version()).as("봇이 뒀다").isGreaterThan(before);
        assertThat(stored.get().turnSeat()).as("봇 차례를 벗어났다").isNotEqualTo(1);
    }
}
