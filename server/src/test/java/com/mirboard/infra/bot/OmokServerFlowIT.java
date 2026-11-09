package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.omok.OmokGameDefinition;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.invariant.OmokInvariantChecker;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.OmokState;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomSettingsUpdate;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * 오목 서버 전 배선 (S3b) — 정의 등록 → 방 설정(D-176) → 시작 이벤트 → 라운드 시작 → 봇 루프(정책) / 턴 타임아웃(자동 수·2연속
 * 시간패) → 매치 종료 → 기록. 그리고 비공개 뷰가 없는 게임의 resync({@code privateHand} 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=omok-flow-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.omok.status=AVAILABLE"
})
class OmokServerFlowIT {

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
    @Autowired OmokStateStore stateStore;
    @Autowired UserRepository users;
    @Autowired UserGameStatsService stats;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void two_bots_finish_a_solo_match_and_it_is_recorded() {
        long hostBot = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBot, "omok-bots", OmokGameDefinition.ID, TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, 2);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(2);

        OmokState state = awaitEnded(room.roomId(), 60);
        assertThat(state.mode()).isEqualTo(OmokMode.SOLO);
        assertRecorded(room.roomId(), 2);
    }

    @Test
    void the_team_mode_setting_makes_a_four_seat_room_that_bots_finish() {
        long hostBot = bots.getBotIds().get(1);
        Room room = roomService.createRoom(hostBot, "omok-team", OmokGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, null);
        assertThat(room.capacity()).as("기본 1:1").isEqualTo(2);
        room = roomService.updateSettings(room.roomId(), hostBot,
                new RoomSettingsUpdate(null, null, null, null, null, Map.of("TEAM_MODE", "TEAMS_2V2", "BOT_LEVEL", "EASY")));
        assertThat(room.capacity()).as("2:2 정원").isEqualTo(4);
        roomService.fillBots(room.roomId(), hostBot);
        room = roomService.startNow(room.roomId(), hostBot);
        assertThat(room.playerIds()).hasSize(4);

        OmokState state = awaitEnded(room.roomId(), 90);
        assertThat(state.mode()).isEqualTo(OmokMode.TEAMS_2V2);
        assertThat(state.result().winnerSeats()).hasSize(state.result().isDraw() ? 0 : 2);
        assertRecorded(room.roomId(), 4);
    }

    /** 사람이 손을 놓으면 턴 타임아웃이 한 번은 대신 두고, 두 번째에 그 팀이 진다 (룰 §8). */
    @Test
    void an_idle_human_on_a_team_is_carried_once_then_loses_the_match_for_the_team() {
        long human = users.save(User.create("om" + UUID.randomUUID().toString().substring(0, 8), "x",
                Clock.systemUTC())).getId();
        Room room = roomService.createRoom(human, "omok-idle", OmokGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, null);
        roomService.updateSettings(room.roomId(), human,
                new RoomSettingsUpdate(null, null, null, null, null, Map.of("TEAM_MODE", "TEAMS_2V2")));
        roomService.fillBots(room.roomId(), human);
        room = roomService.startNow(room.roomId(), human);
        int humanSeat = room.playerIds().indexOf(human);

        OmokState state = awaitEnded(room.roomId(), 60);
        assertThat(state.result().reason()).isEqualTo(EndReason.TIMEOUT);
        assertThat(state.result().causeSeat()).isEqualTo(humanSeat);
        assertThat(state.moves().stream().filter(m -> m.seat() == humanSeat))
                .singleElement().satisfies(m -> assertThat(m.timedOut()).isTrue());

        String roomId = room.roomId();
        Awaitility.await("매치 기록").atMost(10, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM omok_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        GameStats humanStats = stats.get(human, OmokGameDefinition.ID);
        assertThat(humanStats.loseCount()).isEqualTo(1);
        assertThat(humanStats.desertCount()).as("시간패는 탈주가 아니다").isZero();
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    /** 비공개 뷰가 없는 게임 — resync 에 공개 판만 오고 privateHand 는 없다. */
    @Test
    void resync_carries_the_public_board_and_no_private_hand() throws Exception {
        String alice = registerAndLogin("omal" + UUID.randomUUID().toString().substring(0, 4));
        String bob = registerAndLogin("ombo" + UUID.randomUUID().toString().substring(0, 4));
        MvcResult created = mockMvc.perform(post("/api/rooms").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "omok-resync", "gameType", "OMOK"))))
                .andExpect(status().isCreated()).andReturn();
        String roomId = objectMapper.readTree(created.getResponse().getContentAsString()).get("roomId").asText();
        mockMvc.perform(post("/api/rooms/" + roomId + "/join").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        for (String token : new String[] {alice, bob}) {
            mockMvc.perform(get("/api/rooms/" + roomId + "/resync").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phase").value("PLAYING"))
                    .andExpect(jsonPath("$.tableView.mode").value("SOLO"))
                    .andExpect(jsonPath("$.tableView.rule").value("RENJU"))
                    .andExpect(jsonPath("$.tableView.moves.length()").value(0))
                    .andExpect(jsonPath("$.tableView.seatColors.length()").value(2))
                    .andExpect(jsonPath("$.privateHand").doesNotExist())
                    .andExpect(jsonPath("$.turnRemainingMs").exists());
        }
    }

    private String registerAndLogin(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private OmokState awaitEnded(String roomId, int seconds) {
        Awaitility.await("판 종료")
                .atMost(seconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(OmokState::isOver).orElse(false));
        OmokState state = stateStore.load(roomId).orElseThrow();
        OmokInvariantChecker.check(state);
        return state;
    }

    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("매치 기록")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM omok_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM omok_match_participants p"
                        + " JOIN omok_match_results r ON r.id = p.match_id WHERE r.room_id = ?",
                Integer.class, roomId)).isEqualTo(seats);
    }
}
