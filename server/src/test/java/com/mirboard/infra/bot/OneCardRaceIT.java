package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameStompController;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-128 — 원카드 "원카드!/잡기!" 경쟁 창이 실제 서버 경로(STOMP 액션 컨트롤러·엔진 타이머·데드라인 폴러·
 * 탈주 서비스)로 닫히는지. 다섯 결과 — 주인이 먼저(CALLED), 사람이 잡음(CAUGHT), 봇이 잡음(엔진 타이머),
 * 아무도 안 누름(EXPIRED, 엔진 타이머), 창 중 탈주(CANCELLED) — 와 늦게 온 누름의 거절.
 *
 * <p>방이 시작되면 무작위로 나눠진 판을 같은 방 락 안에서 정해 둔 테이블로 바꿔 끼운다(좌석 0 이 ♥9·♣3 으로
 * 차례, ♥9 를 내면 1장). 창 길이·봇 반응은 짧게, 폴링은 촘촘히 잡아 몇 초 안에 끝난다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=onecard-race-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.onecard.status=AVAILABLE",
        "mirboard.onecard.race-window-millis=1000",
        "mirboard.onecard.bot-reaction-owner-min-millis=200",
        "mirboard.onecard.bot-reaction-owner-max-millis=200",
        "mirboard.onecard.bot-reaction-catcher-min-millis=300",
        "mirboard.onecard.bot-reaction-catcher-max-millis=300"
})
class OneCardRaceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired RoomService roomService;
    @Autowired UserRepository users;
    @Autowired OneCardStateStore stateStore;
    @Autowired GameStompController controller;
    @Autowired DesertionService desertion;
    @Autowired TurnTimeoutScheduler turnTimeout;
    @Autowired RoomActionLock lock;
    @Autowired StringRedisTemplate redis;

    private static final PlayingCard HEART_9 = PlayingCard.of(Suit.HEART, 9);

    private long human() {
        String name = "ocr" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    /** 사람만의 방 — 첫 번째가 호스트(좌석 0). */
    private Room humanRoom(long... players) {
        Room room = roomService.createRoom(players[0], "oc-race", "ONE_CARD", TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, players.length);
        for (int i = 1; i < players.length; i++) {
            roomService.joinRoom(room.roomId(), players[i]);
        }
        for (long player : players) {
            room = roomService.setReady(room.roomId(), player, true);
        }
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        return room;
    }

    /** 사람 호스트(좌석 0) + 봇(좌석 1). 봇은 입장 때 자동 준비라 호스트가 준비하면 시작한다. */
    private Room humanAndBotRoom(long host) {
        Room room = roomService.createRoom(host, "oc-race-bot", "ONE_CARD", TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, 2);
        room = roomService.setReady(room.roomId(), host, true);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.botSeats()).containsExactly(1);
        return room;
    }

    /** 좌석 0 이 ♥9·♣3 으로 차례인 테이블. 나머지 좌석은 3장씩, 남는 카드는 뽑을 더미. */
    private static OneCardState aboutToGoDownToOne(int seatCount) {
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

    /** 나눠진 판을 정해 둔 테이블로 바꾼다 — 방 락 안에서 바꿔 봇 루프와 겹치지 않게 한다. */
    private void deal(String roomId, OneCardState table) {
        assertThat(lock.acquireWaiting(roomId)).isTrue();
        try {
            stateStore.save(roomId, table);
        } finally {
            lock.release(roomId);
        }
        turnTimeout.onTurnAdvanced(roomId);
    }

    private void act(String roomId, long userId, Map<String, Object> action) {
        controller.onAction(roomId, action, new AuthPrincipal(userId, "u" + userId));
    }

    private static Map<String, Object> playHeartNine() {
        return Map.of("@action", "PLAY_CARD", "card", Map.of("suit", "HEART", "rank", 9));
    }

    private static Map<String, Object> press(String type, int raceId) {
        return Map.of("@action", type, "raceId", raceId);
    }

    private OneCardState state(String roomId) {
        return stateStore.load(roomId).orElseThrow();
    }

    /** 좌석 0 이 ♥9 를 내 창을 연다. 엔진 타이머가 `deadlines:game` 에 걸렸는지도 본다. */
    private OneCardState openRace(String roomId, long ownerId) {
        act(roomId, ownerId, playHeartNine());
        OneCardState raced = state(roomId);
        assertThat(raced.race()).as("창이 열렸다").isNotNull();
        assertThat(raced.turnSeat()).isEqualTo(-1);
        return raced;
    }

    private boolean engineTimerArmed(String roomId) {
        var members = redis.opsForZSet().range("deadlines:" + EngineTimerScheduler.KIND, 0, -1);
        return members != null && members.stream().anyMatch(member -> member.startsWith(roomId + "#"));
    }

    private static void await(String what, java.util.concurrent.Callable<Boolean> condition) {
        Awaitility.await(what).atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(condition);
    }

    @Test
    void the_owner_calling_first_closes_the_race_safely() {
        long owner = human();
        long other = human();
        String roomId = humanRoom(owner, other).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        OneCardState raced = openRace(roomId, owner);
        assertThat(engineTimerArmed(roomId)).as("엔진 타이머 무장").isTrue();
        act(roomId, owner, press("CALL_ONE_CARD", raced.race().raceId()));

        OneCardState after = state(roomId);
        assertThat(after.race()).isNull();
        assertThat(after.hands().get(0)).hasSize(1);
        assertThat(after.turnSeat()).isEqualTo(1);
        assertThat(engineTimerArmed(roomId)).as("닫힌 창의 타이머는 지워진다").isFalse();
    }

    @Test
    void another_player_catching_first_costs_the_owner_one_card() {
        long owner = human();
        long catcher = human();
        String roomId = humanRoom(owner, catcher).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        OneCardState raced = openRace(roomId, owner);
        act(roomId, catcher, press("CATCH", raced.race().raceId()));

        OneCardState after = state(roomId);
        assertThat(after.race()).isNull();
        assertThat(after.hands().get(0)).hasSize(2);
        assertThat(after.turnSeat()).isEqualTo(1);
    }

    @Test
    void when_nobody_presses_the_engine_timer_closes_the_window_without_penalty() {
        long owner = human();
        long other = human();
        String roomId = humanRoom(owner, other).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        openRace(roomId, owner);

        await("창 만료", () -> state(roomId).race() == null);
        OneCardState after = state(roomId);
        assertThat(after.hands().get(0)).as("벌칙 없음").hasSize(1);
        assertThat(after.turnSeat()).isEqualTo(1);
    }

    @Test
    void a_bot_reacting_within_the_window_catches_a_slow_human() {
        long owner = human();
        String roomId = humanAndBotRoom(owner).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        OneCardState raced = openRace(roomId, owner);
        assertThat(raced.race().botPress()).as("봇이 창 안에 반응하도록 추첨됐다").isNotNull();

        await("봇이 잡음", () -> state(roomId).race() == null || state(roomId).hands().get(0).size() == 2);
        await("벌칙 반영", () -> state(roomId).hands().get(0).size() == 2);
    }

    @Test
    void a_desertion_during_the_race_closes_it_without_penalty_and_the_reserved_seat_plays() {
        long owner = human();
        long next = human();
        long leaver = human();
        String roomId = humanRoom(owner, next, leaver).roomId();
        deal(roomId, aboutToGoDownToOne(3));
        openRace(roomId, owner);

        assertThat(desertion.processDesertion(roomId, leaver)).isTrue();

        OneCardState after = state(roomId);
        assertThat(after.race()).isNull();
        assertThat(after.hands().get(0)).as("벌칙 없음").hasSize(1);
        assertThat(after.turnSeat()).isEqualTo(1);
        assertThat(after.eliminations()).containsExactly(new Elimination(2, Elimination.Reason.DESERTED, 3));
        assertThat(roomService.getRoom(roomId).status()).isEqualTo(RoomStatus.IN_GAME);
    }

    @Test
    void a_press_for_a_closed_race_is_rejected_and_changes_nothing() {
        long owner = human();
        long other = human();
        String roomId = humanRoom(owner, other).roomId();
        deal(roomId, aboutToGoDownToOne(2));
        OneCardState raced = openRace(roomId, owner);
        act(roomId, owner, press("CALL_ONE_CARD", raced.race().raceId()));
        OneCardState closed = state(roomId);

        act(roomId, other, press("CATCH", raced.race().raceId()));

        assertThat(state(roomId)).isEqualTo(closed);
    }
}
