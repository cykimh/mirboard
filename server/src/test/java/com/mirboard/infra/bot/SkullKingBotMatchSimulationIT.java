package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.invariant.SkullKingInvariantChecker;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-102 (S5) 완료 기준 — <b>봇만으로 스컬킹 10라운드 완주</b>. 4인·6인 방을 봇으로 채워
 * 정의 등록 → GameStartingEvent → BotScheduler(포트 기본 botAction) → advance 의 라운드
 * 연쇄 → 매치 종료 → 방 FINISHED 까지 전 배선을 검증한다.
 *
 * <p>6인 방은 봇 풀 확장(V10, 4→8)의 회귀 가드이기도 하다 — 봇 5명이 필요해 V3 4명으로는
 * 방 생성부터 실패한다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=skullking-sim-test-secret-32-bytes-min",
        "mirboard.bot.seed=54321",
        "mirboard.bot.delay-millis=0"
})
class SkullKingBotMatchSimulationIT {

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
    @Autowired SkullKingStateStore stateStore;
    @Autowired SkullKingMatchStateStore matchStateStore;

    @Test
    void four_player_all_bot_match_completes_ten_rounds() {
        runAllBotMatch(4);
    }

    @Test
    void six_player_all_bot_match_completes_ten_rounds() {
        runAllBotMatch(6);
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        // D-106 — 스컬킹은 목표 점수·팀·내기를 안 쓴다. 예전엔 targetScore 에 0(뜻 없는 값)을
        // 넘겼는데, 이제 미지원 옵션에 기본값 아닌 값이 오면 서버가 거절한다. "요청 안 함"을
        // 그대로 표현하려면 기본값을 넘긴다.
        Room room = roomService.createRoom(hostBotId, "sk-bot-sim-" + capacity, "SKULL_KING",
                TeamPolicy.SEQUENTIAL, true, RoomService.DEFAULT_TARGET_SCORE,
                0, RoomService.DEFAULT_STAKE, capacity);
        String roomId = room.roomId();

        // capacity 도달 + 봇 전원 자동 ready → IN_GAME → 리스너가 1라운드 분배.
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(capacity);

        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> matchStateStore.load(roomId)
                        .map(SkullKingMatchState::isMatchOver)
                        .orElse(false));

        SkullKingMatchState match = matchStateStore.load(roomId).orElseThrow();
        assertThat(match.roundNumber())
                .as("10라운드 완주 (탈주 조기 종료 아님)")
                .isEqualTo(SkullKingMatchState.TOTAL_ROUNDS + 1);
        assertThat(match.desertedSeats()).isEmpty();
        assertThat(match.cumulativeScores()).hasSize(capacity);
        assertThat(match.winners()).isNotEmpty();

        // 봇 매치 → MatchProgressService 가 방을 FINISHED 로 전이 (D-82).
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> roomService.getRoom(roomId).status() == RoomStatus.FINISHED);

        // 마지막 라운드 상태 invariant (2-인자 — 탈주 정합 포함).
        stateStore.load(roomId).ifPresent(state ->
                SkullKingInvariantChecker.check(state, match));
    }
}
