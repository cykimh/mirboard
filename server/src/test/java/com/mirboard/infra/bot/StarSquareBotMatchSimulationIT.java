package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.starsquare.StarSquareConfig;
import com.mirboard.domain.game.starsquare.StarSquareEngine;
import com.mirboard.domain.game.starsquare.StarSquareGameDefinition;
import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.invariant.StarSquareInvariantChecker;
import com.mirboard.domain.game.starsquare.persistence.StarSquareStateStore;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import com.mirboard.domain.game.starsquare.state.MatchResult.EndReason;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.DesertionService;
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
 * D-285 (S2) 완료 기준 — 정의 등록 → GameStartingEvent → 라운드 스타터(퍼즐 고르기·준비) → 엔진 타이머(준비 끝·봇 페이스·마감·결과 보기)
 * → 매치 종료 → 방 상태 → 기록까지 전 배선. 시간 배율 {@code mirboard.starsquare.time-scale=0.005} 로 준비·마감·결과 보기·봇 페이스만 줄인다
 * (퍼즐 제한은 방 설정 그대로).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=starsquare-sim-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.starsquare.status=AVAILABLE",
        "mirboard.starsquare.time-scale=0.005"
})
class StarSquareBotMatchSimulationIT {

    private static final double SCALE = 0.005;

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
    @Autowired StarSquareStateStore stateStore;
    @Autowired UserRepository users;
    @Autowired UserGameStatsService stats;
    @Autowired DesertionService desertion;
    @Autowired JdbcTemplate jdbc;

    @Test
    void two_bots_finish_a_match() {
        runAllBotMatch(2);
    }

    @Test
    void four_bots_finish_a_match() {
        runAllBotMatch(4);
    }

    /** 사람은 손을 놓고 봇 둘이 푼다 — 봇의 첫 완성 + 30초(배율) 마감이 퍼즐마다 끝내고, 사람의 전적은 남되 ELO 는 제외. */
    @Test
    void an_idle_human_with_bots_is_closed_out_by_the_bots_and_keeps_a_record_without_elo() {
        long human = newUser("sq_idle_");
        Room room = roomService.createRoom(human, "sq-idle", StarSquareGameDefinition.ID, TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds — 자동*/ 0, RoomService.DEFAULT_STAKE, 3);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);

        StarSquareState ended = awaitEnded(room.roomId(), 60, Set.of(1, 2), 0);
        assertThat(ended.result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(ended.outcomes()).hasSize(3);
        assertThat(ended.outcomes()).extracting(PuzzleOutcome::reason).containsOnly(PuzzleOutcome.EndReason.CLOSED);
        assertThat(ended.outcomes()).allMatch(o -> !o.results().get(0).solved()
                && o.results().stream().anyMatch(r -> r.solved()));
        assertRecorded(room.roomId(), 3);
        GameStats humanStats = stats.get(human, StarSquareGameDefinition.ID);
        assertThat(humanStats.winCount() + humanStats.loseCount()).as("사람 전적 1판").isEqualTo(1);
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    /** 혼자 하기: "1명으로 시작" → 퍼즐 제한(1초)마다 끝나 판이 끝나고, 기록 0행·전적 없음, 대기실로 돌아간다. */
    @Test
    void a_solo_match_ends_without_any_record_and_returns_to_the_waiting_room() {
        long human = newUser("sq_solo_");
        Room room = roomService.createRoom(human, "sq-solo", StarSquareGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, null);
        Room started = roomService.startNow(room.roomId(), human);
        assertThat(started.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(started.playerIds()).containsExactly(human);

        StarSquareState ended = awaitEnded(room.roomId(), 30, Set.of(), 1);
        assertThat(ended.result().reason()).isEqualTo(EndReason.FINISHED);
        assertThat(ended.outcomes()).extracting(PuzzleOutcome::reason).containsOnly(PuzzleOutcome.EndReason.TIME_UP);
        Awaitility.await("대기실 복귀").atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .until(() -> roomService.getRoom(room.roomId()).status() == RoomStatus.WAITING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM star_square_match_results WHERE room_id = ?",
                Integer.class, room.roomId())).isZero();
        GameStats solo = stats.get(human, StarSquareGameDefinition.ID);
        assertThat(solo.winCount() + solo.loseCount()).as("1인 판은 전적 없음").isZero();
    }

    /** 사람 둘 중 하나가 탈주 → 남은 좌석이 하나라 LAST_SEAT, 남은 사람이 1위. 탈주자는 패 + 탈주 수. */
    @Test
    void a_desertion_leaving_one_seat_ends_the_match_for_the_last_seat() {
        long a = newUser("sq_a_");
        long b = newUser("sq_b_");
        Room room = roomService.createRoom(a, "sq-desert", StarSquareGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, null);
        roomService.joinRoom(room.roomId(), b);
        Room started = roomService.startNow(room.roomId(), a);
        assertThat(started.playerIds()).containsExactly(a, b);
        Awaitility.await("첫 퍼즐").atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> stateStore.load(room.roomId()).map(s -> s.phase().name().equals("PLAYING")).orElse(false));

        assertThat(desertion.processDesertion(room.roomId(), b)).isTrue();
        StarSquareState ended = stateStore.load(room.roomId()).orElseThrow();
        assertThat(ended.result().reason()).isEqualTo(EndReason.LAST_SEAT);
        assertThat(ended.result().winners()).containsExactly(0);
        assertRecorded(room.roomId(), 2);
        assertThat(jdbc.queryForObject("SELECT deserted FROM star_square_match_participants p JOIN star_square_match_results r"
                + " ON r.id = p.match_id WHERE r.room_id = ? AND p.user_id = ?", Boolean.class, room.roomId(), b)).isTrue();
        GameStats winner = stats.get(a, StarSquareGameDefinition.ID);
        assertThat(winner.winCount()).isEqualTo(1);
        assertThat(roomService.getRoom(room.roomId()).status()).isEqualTo(RoomStatus.FINISHED);
    }

    private long newUser(String prefix) {
        return users.save(User.create(prefix + UUID.randomUUID().toString().substring(0, 6), "x",
                Clock.systemUTC())).getId();
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBotId, "sq-bot-sim-" + capacity, StarSquareGameDefinition.ID,
                TeamPolicy.SEQUENTIAL, true, RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, capacity);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        Set<Integer> botSeats = new HashSet<>();
        for (int i = 0; i < capacity; i++) {
            botSeats.add(i);
        }
        StarSquareState ended = awaitEnded(room.roomId(), 60, botSeats, 0);
        assertThat(ended.result().reason()).isEqualTo(EndReason.FINISHED);
        // 봇끼리는 모두 완성하거나, 봇 완성 시각의 흩어짐이 첫 완성 + 30초보다 넓으면 마감으로 끝난다 — 어느 쪽이든 누군가는 풀었다
        assertThat(ended.outcomes()).extracting(PuzzleOutcome::reason)
                .allMatch(r -> r == PuzzleOutcome.EndReason.ALL_SOLVED || r == PuzzleOutcome.EndReason.CLOSED);
        assertThat(ended.outcomes()).allMatch(o -> o.results().stream().anyMatch(r -> r.solved()));
        assertRecorded(room.roomId(), capacity);
    }

    private StarSquareState awaitEnded(String roomId, int seconds, Set<Integer> botSeats, int turnSeconds) {
        Awaitility.await("판 종료").atMost(seconds, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(StarSquareState::ended).orElse(false));
        StarSquareState state = stateStore.load(roomId).orElseThrow();
        StarSquareEngine engine = new StarSquareEngine(new StarSquareConfig(state.seatCount(), botSeats,
                state.puzzleCount(), Difficulty.NORMAL, BotLevel.NORMAL, turnSeconds, SCALE));
        StarSquareInvariantChecker.check(state, engine);
        assertThat(state.result().placements()).hasSize(state.seatCount());
        return state;
    }

    /** 매치 결과 1행 + 좌석 수만큼 참가자. */
    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("매치 기록").atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject("SELECT COUNT(*) FROM star_square_match_results WHERE room_id = ?",
                        Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM star_square_match_participants p"
                + " JOIN star_square_match_results r ON r.id = p.match_id WHERE r.room_id = ?", Integer.class, roomId))
                .isEqualTo(seats);
    }
}
