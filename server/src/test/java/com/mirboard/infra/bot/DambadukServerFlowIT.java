package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.dambaduk.DambadukGameDefinition;
import com.mirboard.domain.game.dambaduk.invariant.DambadukInvariantChecker;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 담바둑 서버 전 배선 (S2, D-278) — 정의 등록 → 시작 이벤트 → 라운드 시작(선 추첨) → 봇 루프(정책) / 턴 타임아웃(자동 수·연속 2번째 빠짐)
 * / 탈주(3인 계속) → 매치 종료 → 기록. 그리고 비공개 뷰가 없는 게임의 resync({@code privateHand} 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=dambaduk-flow-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.dambaduk.status=AVAILABLE"
})
class DambadukServerFlowIT {

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
    @Autowired DambadukStateStore stateStore;
    @Autowired UserRepository users;
    @Autowired UserGameStatsService stats;
    @Autowired DesertionService desertion;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void two_bots_finish_a_match_and_it_is_recorded() {
        runAllBotMatch(2);
    }

    @Test
    void three_bots_finish_a_match_and_it_is_recorded() {
        runAllBotMatch(3);
    }

    /** 3인 — 사람이 판 도중 탈주하면 돌을 치우고 봇 둘이 끝까지 둔다. 사람은 패 + 탈주 1. */
    @Test
    void a_human_deserting_a_three_player_match_leaves_the_bots_to_finish_it() {
        long human = human("dbdes");
        Room room = roomService.createRoom(human, "db-desert", DambadukGameDefinition.ID, TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 0, RoomService.DEFAULT_STAKE, 3);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        String roomId = room.roomId();
        int humanSeat = room.playerIds().indexOf(human);
        Awaitility.await("사람 차례").atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> stateStore.load(roomId).map(s -> s.turnSeat() == humanSeat).orElse(false));

        assertThat(desertion.processDesertion(roomId, human)).isTrue();
        DambadukState afterDesertion = stateStore.load(roomId).orElseThrow();
        assertThat(afterDesertion.isOver()).isFalse();
        assertThat(afterDesertion.stonesOf(humanSeat)).isEmpty();

        DambadukState ended = awaitEnded(roomId, 60);
        assertThat(ended.exits()).anyMatch(e -> e.seat() == humanSeat && e.reason() == ExitReason.DESERTED);
        assertRecorded(roomId, 3);
        GameStats humanStats = stats.get(human, DambadukGameDefinition.ID);
        assertThat(humanStats.loseCount()).isEqualTo(1);
        assertThat(humanStats.desertCount()).isEqualTo(1);
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    /** 2인 — 사람이 손을 놓으면 턴 타임아웃이 한 번은 대신 두고, 두 번째에 그 사람이 진다(룰 §8, 탈주 아님). */
    @Test
    void an_idle_human_is_carried_once_then_loses_on_the_second_timeout() {
        long human = human("dbidle");
        Room room = roomService.createRoom(human, "db-idle", DambadukGameDefinition.ID, TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, 2);
        int humanSeat = room.playerIds().indexOf(human);

        DambadukState ended = awaitEnded(room.roomId(), 60);
        assertThat(ended.result().reason()).isEqualTo(EndReason.TIMEOUT);
        assertThat(ended.result().causeSeat()).isEqualTo(humanSeat);
        String roomId = room.roomId();
        Awaitility.await("매치 기록").atMost(10, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM dambaduk_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        GameStats humanStats = stats.get(human, DambadukGameDefinition.ID);
        assertThat(humanStats.loseCount()).isEqualTo(1);
        assertThat(humanStats.desertCount()).as("시간패는 탈주가 아니다").isZero();
    }

    /** 비공개 뷰가 없는 게임 — resync 에 공개 판(서버가 계산한 터 포함)만 오고 privateHand 는 없다. */
    @Test
    void resync_carries_the_public_board_and_no_private_hand() throws Exception {
        String alice = registerAndLogin("dbal" + UUID.randomUUID().toString().substring(0, 4));
        String bob = registerAndLogin("dbbo" + UUID.randomUUID().toString().substring(0, 4));
        MvcResult created = mockMvc.perform(post("/api/rooms").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "db-resync", "gameType", "DAMBADUK"))))
                .andExpect(status().isCreated()).andReturn();
        String roomId = objectMapper.readTree(created.getResponse().getContentAsString()).get("roomId").asText();
        mockMvc.perform(post("/api/rooms/" + roomId + "/join").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        for (String token : new String[] {alice, bob}) {
            mockMvc.perform(get("/api/rooms/" + roomId + "/resync").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phase").value("PLACING"))
                    .andExpect(jsonPath("$.tableView.seatCount").value(2))
                    .andExpect(jsonPath("$.tableView.stones.length()").value(4))
                    .andExpect(jsonPath("$.tableView.walls.length()").value(0))
                    .andExpect(jsonPath("$.tableView.territory.regions.length()").value(1))
                    .andExpect(jsonPath("$.tableView.placeKinds[0]").value("STRAIGHT"))
                    .andExpect(jsonPath("$.privateHand").doesNotExist())
                    .andExpect(jsonPath("$.turnRemainingMs").exists());
        }
    }

    private long human(String prefix) {
        return users.save(User.create(prefix + UUID.randomUUID().toString().substring(0, 4), "x", Clock.systemUTC()))
                .getId();
    }

    private void runAllBotMatch(int capacity) {
        long hostBot = bots.getBotIds().get(capacity - 2);
        Room room = roomService.createRoom(hostBot, "db-bots-" + capacity, DambadukGameDefinition.ID,
                TeamPolicy.SEQUENTIAL, true, RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, capacity);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(capacity);

        DambadukState ended = awaitEnded(room.roomId(), 60);
        assertThat(ended.seatCount()).isEqualTo(capacity);
        assertThat(ended.result().reason()).isIn(EndReason.SEPARATED, EndReason.FROZEN);
        assertRecorded(room.roomId(), capacity);
    }

    private DambadukState awaitEnded(String roomId, int seconds) {
        Awaitility.await("판 종료")
                .atMost(seconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(DambadukState::isOver).orElse(false));
        DambadukState state = stateStore.load(roomId).orElseThrow();
        DambadukInvariantChecker.check(state);
        return state;
    }

    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("매치 기록")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM dambaduk_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dambaduk_match_participants p"
                        + " JOIN dambaduk_match_results r ON r.id = p.match_id WHERE r.room_id = ?",
                Integer.class, roomId)).isEqualTo(seats);
    }

    private String registerAndLogin(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }
}
