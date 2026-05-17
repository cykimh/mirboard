package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.invariant.TichuInvariantChecker;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 13D(#6) — 턴 타임아웃 자동 진행 IT.
 *
 * <p>비-봇 host(seat 0) + 봇 3명, turnSeconds=1. seat 0 은 아무도 행동하지
 * 않으므로 BotScheduler 가 처리 못 함 → 매 턴 ~1초 후 TurnTimeoutScheduler 가
 * 자동 안전행동(Ready/PassCards/PassTrick/약한카드)을 적용해 매치가 끝까지
 * 진행되어야 한다. targetScore=300 으로 빠르게 종료. 매치 종료 후 invariant 검증.
 */
@SpringBootTest
@Testcontainers
@Import(TurnTimeoutSchedulerIT.TestSinkConfig.class)
@TestPropertySource(properties = {
        "mirboard.jwt.secret=turn-timeout-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=777",
        "mirboard.bot.delay-millis=0"
})
class TurnTimeoutSchedulerIT {

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
    @Autowired MatchCompletedSink sink;
    @Autowired TichuGameStateStore stateStore;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

    @Test
    void idle_human_seat_is_auto_advanced_until_match_completes() {
        sink.clear();
        // host = 비-봇 실제 등록 유저 (seat 0). 절대 행동 안 함 → 타임아웃이 대신
        // 진행. Phase 16(#4): 봇 매치도 전적 기록되므로 host 는 실재 users 행이어야
        // participant FK(users.id) 위반 없이 매치 종료가 정상 발행됨.
        long humanHostId = userRepo
                .save(User.create("tt_human_host", passwordEncoder.encode("validpass1"), clock))
                .getId();
        Room room = roomService.createRoom(
                humanHostId, "turn-timeout", "TICHU",
                TeamPolicy.SEQUENTIAL, /*fillWithBots*/ true,
                /*targetScore*/ 300, /*turnSeconds*/ 1);
        String roomId = room.roomId();

        // Phase 16(#2) — 봇 3 은 자동 ready. 사람 호스트가 준비하면 전원 ready
        // → 게임 시작 (이후 게임 중엔 의도적으로 행동 안 함 = idle).
        room = roomService.setReady(roomId, humanHostId, true);

        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(4);
        assertThat(room.turnSeconds()).isEqualTo(1);
        assertThat(room.botSeats()).containsExactly(1, 2, 3); // seat 0 = 비-봇

        Awaitility.await()
                .atMost(120, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> sink.byRoom(roomId).isDone());

        TichuMatchCompleted completed = sink.byRoom(roomId).join();
        assertThat(completed.roomId()).isEqualTo(roomId);
        assertThat(completed.winningTeam()).isNotNull();
        stateStore.load(roomId).ifPresent(TichuInvariantChecker::check);
    }

    @TestConfiguration
    static class TestSinkConfig {
        @Bean
        MatchCompletedSink matchCompletedSink() {
            return new MatchCompletedSink();
        }
    }

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
