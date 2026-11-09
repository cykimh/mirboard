package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.cloudroute.CloudRouteGameDefinition;
import com.mirboard.domain.game.cloudroute.invariant.CloudRouteInvariantChecker;
import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
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
 * D-212 (S2) 완료 기준 — <b>봇만으로 구름항로 한 판 완주</b>. 정의 등록 → GameStartingEvent → 라운드 시작 → BotScheduler(휴리스틱
 * botAction) → 매치 종료 → 방 FINISHED → 기록까지 전 배선을 본다. 손을 놓은 사람이 낀 방은 턴 타임아웃(아무것도 안 함·굴리기)이
 * 결정마다 대신 진행해 끝까지 간다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=cloudroute-sim-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.cloudroute.status=AVAILABLE"
})
class CloudRouteBotMatchSimulationIT {

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
    @Autowired CloudRouteStateStore stateStore;
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

    @Test
    void three_bots_finish_a_match() {
        runAllBotMatch(3);
    }

    /** 사람 호스트가 손을 놓으면 턴 타임아웃이 결정마다 대신 진행한다(굴리기·안 사기) — 판은 끝나고 사람의 전적이 남는다. */
    @Test
    void an_idle_human_is_carried_by_turn_timeouts_until_the_match_ends() {
        long human = users.save(User.create("cr_idle_" + UUID.randomUUID().toString().substring(0, 8), "x",
                Clock.systemUTC())).getId();
        Room room = roomService.createRoom(human, "cr-idle", "CLOUD_ROUTE", TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, 4);
        // D-136 — 봇과 연습 방은 만들자마자 시작한다(방장 준비까지 서버가 넣는다).
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        String roomId = room.roomId();

        awaitEnded(roomId, 240);

        assertRecorded(roomId, 4);
        GameStats humanStats = stats.get(human, CloudRouteGameDefinition.ID);
        assertThat(humanStats.winCount() + humanStats.loseCount()).as("사람 전적 1판").isEqualTo(1);
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBotId, "cr-bot-sim-" + capacity, "CLOUD_ROUTE", TeamPolicy.SEQUENTIAL,
                true, RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, capacity);
        String roomId = room.roomId();

        // capacity 도달 + 봇 전원 자동 ready → IN_GAME → 리스너가 분배.
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(capacity);

        awaitEnded(roomId, 60);
        assertRecorded(roomId, capacity);
    }

    private void awaitEnded(String roomId, int seconds) {
        Awaitility.await("판 종료")
                .atMost(seconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(CloudRouteState::ended).orElse(false));
        CloudRouteState state = stateStore.load(roomId).orElseThrow();
        CloudRouteInvariantChecker.check(state);
        assertThat(state.result().standings()).hasSize(state.seatCount());
    }

    /** 1판 = 1매치 → 방 FINISHED, 매치 결과 1행 + 좌석 수만큼 참가자. */
    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("방 FINISHED")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> roomService.getRoom(roomId).status() == RoomStatus.FINISHED);
        Awaitility.await("매치 기록")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM cloudroute_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cloudroute_match_participants p"
                        + " JOIN cloudroute_match_results r ON r.id = p.match_id WHERE r.room_id = ?",
                Integer.class, roomId)).isEqualTo(seats);
    }
}
