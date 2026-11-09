package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.yacht.YachtGameDefinition;
import com.mirboard.domain.game.yacht.invariant.YachtInvariantChecker;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.YachtState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 요트 S3 완료 기준 — 정의 등록 → GameStartingEvent → 라운드 시작 → BotScheduler(휴리스틱) → 매치 종료 → 기록까지 전 배선.
 * 손을 놓은 사람은 <b>차례 전체 제한(엔진 타이머, D-183)</b>이 자동 기록으로 대신 진행해 끝까지 간다. 1인 판은 끝나도 아무것도
 * 기록하지 않고(D-181) 대기실로 돌아간다(D-153).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=yacht-sim-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        // 기본 DISABLED(완성 전) — 방을 만들려면 연다.
        "mirboard.yacht.status=AVAILABLE"
})
class YachtBotMatchSimulationIT {

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
    @Autowired BotUserRegistry bots;
    @Autowired YachtStateStore stateStore;
    @Autowired UserRepository users;
    @Autowired UserGameStatsService stats;
    @Autowired JdbcTemplate jdbc;

    @Test
    void two_bots_finish_a_match() {
        runAllBotMatch(2);
    }

    @Test
    void four_bots_finish_a_match() {
        runAllBotMatch(4);
    }

    /** 사람 호스트가 손을 놓으면 차례 전체 제한(1초)의 자동 기록이 대신 진행한다 — 판은 끝나고 사람의 전적이 남는다. */
    @Test
    void an_idle_human_is_carried_by_the_whole_turn_timer_until_the_match_ends() {
        long human = newUser("yt_idle_");
        Room room = roomService.createRoom(human, "yt-idle", YachtGameDefinition.ID, TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, 2);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);

        YachtState ended = awaitEnded(room.roomId(), 60, Set.of(1));
        assertThat(ended.result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(ended.card(0).isFull()).as("사람 점수표도 12칸 자동 기록으로 다 찼다").isTrue();
        assertRecorded(room.roomId(), 2);
        GameStats humanStats = stats.get(human, YachtGameDefinition.ID);
        assertThat(humanStats.winCount() + humanStats.loseCount()).as("사람 전적 1판").isEqualTo(1);
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    /** D-181 — 혼자 하기: 방장 혼자 "1명으로 시작", 끝나면 기록 0행, 대기실로 돌아간다. */
    @Test
    void a_solo_match_ends_without_any_record_and_returns_to_the_waiting_room() {
        long human = newUser("yt_solo_");
        Room room = roomService.createRoom(human, "yt-solo", YachtGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, null);
        assertThat(room.status()).isEqualTo(RoomStatus.WAITING);
        Room started = roomService.startNow(room.roomId(), human);
        assertThat(started.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(started.playerIds()).containsExactly(human);

        YachtState ended = awaitEnded(room.roomId(), 60, Set.of());
        assertThat(ended.result().reason()).isEqualTo(EndReason.FINISHED);
        Awaitility.await("대기실 복귀")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> roomService.getRoom(room.roomId()).status() == RoomStatus.WAITING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yacht_match_results WHERE room_id = ?",
                Integer.class, room.roomId())).isZero();
        GameStats soloStats = stats.get(human, YachtGameDefinition.ID);
        assertThat(soloStats.winCount() + soloStats.loseCount()).as("1인 판은 전적 없음").isZero();
    }

    private long newUser(String prefix) {
        return users.save(User.create(prefix + UUID.randomUUID().toString().substring(0, 6), "x",
                Clock.systemUTC())).getId();
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBotId, "yt-bot-sim-" + capacity, YachtGameDefinition.ID,
                TeamPolicy.SEQUENTIAL, true, RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, capacity);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(capacity);

        Set<Integer> botSeats = new HashSet<>();
        for (int i = 0; i < capacity; i++) {
            botSeats.add(i);
        }
        YachtState ended = awaitEnded(room.roomId(), 60, botSeats);
        assertThat(ended.result().reason()).isEqualTo(EndReason.FINISHED);
        assertRecorded(room.roomId(), capacity);
    }

    private YachtState awaitEnded(String roomId, int seconds, Set<Integer> botSeats) {
        Awaitility.await("판 종료")
                .atMost(seconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(YachtState::ended).orElse(false));
        YachtState state = stateStore.load(roomId).orElseThrow();
        YachtInvariantChecker.check(state, botSeats);
        assertThat(state.result().placements()).hasSize(state.seatCount());
        return state;
    }

    /** 매치 결과 1행 + 좌석 수만큼 참가자. */
    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("매치 기록")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM yacht_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM yacht_match_participants p"
                        + " JOIN yacht_match_results r ON r.id = p.match_id WHERE r.room_id = ?",
                Integer.class, roomId)).isEqualTo(seats);
    }
}
