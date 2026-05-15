package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.invariant.TichuInvariantChecker;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 9E — 솔로 모드 풀-게임 시뮬레이션 IT.
 *
 * <p>4 좌석 모두 봇으로 채워 매치 종료까지 진행 — 회귀 안전망. 코드 변경 후
 * 봇 무한 루프 / dead state / 예외가 발생하면 본 테스트가 catch.
 *
 * <p>봇 딜레이 0 + 시드 고정으로 빠르고 reproducible.
 */
@SpringBootTest
@Testcontainers
@Import(BotMatchSimulationIT.TestSinkConfig.class)
@TestPropertySource(properties = {
        "mirboard.jwt.secret=bot-sim-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=12345",
        "mirboard.bot.delay-millis=0"
})
class BotMatchSimulationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired RoomService roomService;
    @Autowired BotUserRegistry bots;
    @Autowired MatchCompletedSink sink;
    @Autowired TichuGameStateStore stateStore;

    /**
     * 4 봇 매치 1회: fillWithBots=true 로 만든 방에서 host 가 봇이면 모든 좌석이 봇 →
     * BotScheduler 만으로 끝까지 진행. 매치 종료 시 Room 이 FINISHED, MatchCompleted
     * 이벤트 발행 확인.
     */
    @Test
    void all_bot_match_completes_within_timeout() {
        sink.clear();
        // host 도 봇 user 로 — 4 좌석 모두 봇.
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(
                hostBotId, "all-bot-sim", "TICHU", TeamPolicy.SEQUENTIAL, true);
        String roomId = room.roomId();

        // capacity 도달 → IN_GAME → BotScheduler 가 진행.
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(4);

        // MatchCompleted 이벤트 도착까지 대기 (timeout 60s).
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> sink.byRoom(roomId).isDone());

        TichuMatchCompleted completed = sink.byRoom(roomId).join();
        assertThat(completed.roomId()).isEqualTo(roomId);
        assertThat(completed.cumulativeTeamAScore() + completed.cumulativeTeamBScore())
                .isGreaterThan(0);
        assertThat(completed.winningTeam()).isNotNull();

        // Phase 10D — 매치 종료 직후 마지막 state invariant 검증.
        stateStore.load(roomId).ifPresent(TichuInvariantChecker::check);
    }

    /**
     * 10 매치 연속 — 무한 루프 / dead state / 예외 0건 확인. 회귀 테스트 시
     * mirboard.bot.simulation-count system property 로 N 회 늘려 stress.
     */
    @Test
    void multiple_bot_matches_no_regressions() {
        int count = Integer.parseInt(System.getProperty("mirboard.bot.simulation-count", "10"));
        for (int i = 0; i < count; i++) {
            sink.clear();
            long hostBotId = bots.getBotIds().get(0);
            Room room = roomService.createRoom(
                    hostBotId, "bot-sim-" + i, "TICHU", TeamPolicy.SEQUENTIAL, true);
            String roomId = room.roomId();

            Awaitility.await()
                    .atMost(60, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> sink.byRoom(roomId).isDone());

            TichuMatchCompleted completed = sink.byRoom(roomId).join();
            assertThat(completed.roomId()).isEqualTo(roomId);

            // Phase 10D — 매 매치 종료 후 invariant 검증
            stateStore.load(roomId).ifPresent(TichuInvariantChecker::check);
        }
    }

    @TestConfiguration
    static class TestSinkConfig {
        @Bean
        MatchCompletedSink matchCompletedSink() {
            return new MatchCompletedSink();
        }
    }

    /**
     * MatchCompleted 이벤트를 room 별로 캡처하는 테스트 sink. DomainEventBus 의
     * 동기 listener — IT scope spring bean 으로 등록.
     */
    static class MatchCompletedSink {
        private final ConcurrentHashMap<String, CompletableFuture<TichuMatchCompleted>>
                completedByRoom = new ConcurrentHashMap<>();

        @EventListener
        public void on(TichuMatchCompleted event) {
            futureFor(event.roomId()).complete(event);
        }

        CompletableFuture<TichuMatchCompleted> byRoom(String roomId) {
            return futureFor(roomId);
        }

        void clear() {
            completedByRoom.clear();
        }

        private CompletableFuture<TichuMatchCompleted> futureFor(String roomId) {
            return completedByRoom.computeIfAbsent(roomId, k -> new CompletableFuture<>());
        }
    }
}
