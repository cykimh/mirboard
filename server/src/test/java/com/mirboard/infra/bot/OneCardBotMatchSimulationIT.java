package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
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
 * D-128 (S3) 완료 기준 — <b>봇만으로 원카드 한 판 완주</b>. 정의 등록 → GameStartingEvent → 라운드 시작 →
 * BotScheduler(휴리스틱 botAction) → 엔진 타이머(봇의 외치기 누름) → 매치 종료 → 방 FINISHED → 기록까지 전
 * 배선을 본다. 손을 놓은 사람이 낀 방은 턴 타임아웃(먹기)이 대신 진행해 끝까지 간다.
 *
 * <p>경쟁 창·봇 반응·폴링은 짧게 잡는다 — 판마다 경쟁이 여러 번 열리므로 운영값(3초)이면 수십 초가 걸린다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=onecard-sim-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        // D-131 기본값과 같지만 지우지 말 것 — 환경변수 MIRBOARD_ONECARD_STATUS(되돌리기 시험으로 COMING_SOON 을 export 한
        // 개발 환경, .env.example)는 기본값을 이기지만 테스트 속성은 못 이긴다. 환경의 되돌리기 설정과 무관하게 연다.
        "mirboard.onecard.status=AVAILABLE",
        "mirboard.onecard.race-window-millis=300",
        "mirboard.onecard.bot-reaction-owner-min-millis=20",
        "mirboard.onecard.bot-reaction-owner-max-millis=60",
        "mirboard.onecard.bot-reaction-catcher-min-millis=20",
        "mirboard.onecard.bot-reaction-catcher-max-millis=60"
})
class OneCardBotMatchSimulationIT {

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
    @Autowired OneCardStateStore stateStore;
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
    void six_bots_finish_a_match() {
        runAllBotMatch(6);
    }

    /** 사람 호스트가 손을 놓으면 턴 타임아웃이 먹기로 대신 진행한다 — 판은 끝나고 사람의 전적이 남는다. */
    @Test
    void an_idle_human_is_carried_by_turn_timeouts_until_the_match_ends() {
        long human = users.save(User.create("oc_idle_" + UUID.randomUUID().toString().substring(0, 8), "x",
                Clock.systemUTC())).getId();
        Room room = roomService.createRoom(human, "oc-idle", "ONE_CARD", TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, 4);
        room = roomService.setReady(room.roomId(), human, true);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        String roomId = room.roomId();

        awaitEnded(roomId, 120);

        assertRecorded(roomId, 4);
        GameStats humanStats = stats.get(human, OneCardGameDefinition.ID);
        assertThat(humanStats.winCount() + humanStats.loseCount()).as("사람 전적 1판").isEqualTo(1);
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBotId, "oc-bot-sim-" + capacity, "ONE_CARD", TeamPolicy.SEQUENTIAL,
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
                .until(() -> stateStore.load(roomId).map(OneCardState::ended).orElse(false));
        OneCardState state = stateStore.load(roomId).orElseThrow();
        OneCardInvariantChecker.check(state);
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
                        "SELECT COUNT(*) FROM onecard_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM onecard_match_participants p"
                        + " JOIN onecard_match_results r ON r.id = p.match_id WHERE r.room_id = ?",
                Integer.class, roomId)).isEqualTo(seats);
    }
}
