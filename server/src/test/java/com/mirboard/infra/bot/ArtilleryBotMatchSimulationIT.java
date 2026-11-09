package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.artillery.ArtilleryGameDefinition;
import com.mirboard.domain.game.artillery.invariant.ArtilleryInvariantChecker;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
 * 씨앗포 S2 완료 기준 (D-222) — 정의 등록 → GameStartingEvent → 라운드 시작 → BotScheduler("보통" 봇) → 발사 → 연출 대기(엔진 타이머)
 * → 다음 차례 … → 매치 종료 → 기록까지 전 배선. 연출 대기는 테스트에서만 상한을 0 으로 줄인다({@code resolve-cap-millis}).
 * 손을 놓은 사람은 턴 시간 초과(넘김)로 진행되고, 사람이 탈락하면 봇만 남아 바로 끝난다(NO_HUMANS).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=artillery-sim-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.artillery.status=AVAILABLE",
        "mirboard.artillery.resolve-cap-millis=0"
})
class ArtilleryBotMatchSimulationIT {

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
    @Autowired ArtilleryStateStore stateStore;
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

    /** 사람 호스트가 손을 놓으면 턴 시간 초과(1초, 넘김)로 진행된다 — 판은 끝나고 사람의 전적이 남는다(봇 판이라 ELO 제외). */
    @Test
    void an_idle_human_is_skipped_by_the_turn_timeout_until_the_match_ends() {
        long human = users.save(User.create("ar_idle_" + UUID.randomUUID().toString().substring(0, 6), "x",
                Clock.systemUTC())).getId();
        Room room = roomService.createRoom(human, "ar-idle", ArtilleryGameDefinition.ID, TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, 2);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);

        awaitEnded(room.roomId(), 120);
        assertRecorded(room.roomId(), 2);
        GameStats humanStats = stats.get(human, ArtilleryGameDefinition.ID);
        assertThat(humanStats.winCount() + humanStats.loseCount()).as("사람 전적 1판").isEqualTo(1);
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBotId, "ar-bot-sim-" + capacity, ArtilleryGameDefinition.ID,
                TeamPolicy.SEQUENTIAL, true, RoomService.DEFAULT_TARGET_SCORE, 30, RoomService.DEFAULT_STAKE, capacity);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(capacity);
        ArtilleryState ended = awaitEnded(room.roomId(), 120);
        assertThat(ended.botSeats()).hasSize(capacity);
        assertRecorded(room.roomId(), capacity);
    }

    private ArtilleryState awaitEnded(String roomId, int seconds) {
        Awaitility.await("판 종료")
                .atMost(seconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(ArtilleryState::ended).orElse(false));
        ArtilleryState state = stateStore.load(roomId).orElseThrow();
        assertThat(ArtilleryInvariantChecker.check(state)).isEmpty();
        assertThat(state.result().placements()).hasSize(state.seatCount());
        return state;
    }

    /** 매치 결과 1행 + 좌석 수만큼 참가자. */
    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("매치 기록")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM artillery_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM artillery_match_participants p"
                        + " JOIN artillery_match_results r ON r.id = p.match_id WHERE r.room_id = ?",
                Integer.class, roomId)).isEqualTo(seats);
        assertThat(jdbc.queryForList("SELECT final_rank FROM artillery_match_participants p"
                + " JOIN artillery_match_results r ON r.id = p.match_id WHERE r.room_id = ?", Integer.class, roomId))
                .contains(1);
    }
}
