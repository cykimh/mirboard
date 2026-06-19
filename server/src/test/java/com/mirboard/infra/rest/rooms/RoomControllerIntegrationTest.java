package com.mirboard.infra.rest.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-controller-test-secret-must-be-32-bytes-or-more"
})
class RoomControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> MYSQL = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    com.mirboard.domain.lobby.auth.UserRepository userRepo;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    @Test
    void create_then_list_then_get_then_leave() throws Exception {
        String token = registerAndLogin("rooms_user", "validpass1");

        // Create
        var createBody = objectMapper.writeValueAsString(
                Map.of("name", "친구들 한 판", "gameType", "TICHU"));
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameType").value("TICHU"))
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.playerCount").value(1))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        String roomId = createdJson.get("roomId").asText();
        assertThat(roomId).isNotBlank();

        // List (with gameType filter)
        mockMvc.perform(get("/api/rooms")
                        .param("gameType", "TICHU")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].roomId").value(roomId))
                .andExpect(jsonPath("$.rooms[0].name").value("친구들 한 판"));

        // Get single
        mockMvc.perform(get("/api/rooms/" + roomId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId));

        // Leave (host alone → room is destroyed)
        mockMvc.perform(post("/api/rooms/" + roomId + "/leave")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/rooms/" + roomId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void create_with_unavailable_game_returns_404() throws Exception {
        String token = registerAndLogin("bad_game_user", "validpass1");

        var body = objectMapper.writeValueAsString(
                Map.of("name", "wrong game", "gameType", "NOT_A_REAL_GAME"));
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GAME_NOT_AVAILABLE"));
    }

    @Test
    void list_without_token_is_unauthorized() throws Exception {
        mockMvc.perform(get("/api/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void joining_unknown_room_is_404() throws Exception {
        String token = registerAndLogin("nonexistent_join", "validpass1");

        mockMvc.perform(post("/api/rooms/00000000-0000-0000-0000-000000000000/join")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void create_with_fillWithBots_waits_for_host_ready_then_starts() throws Exception {
        String token = registerAndLogin("solo_user", "validpass1");

        var body = objectMapper.writeValueAsString(Map.of(
                "name", "솔로 모드",
                "gameType", "TICHU",
                "fillWithBots", true));
        // Phase 16(#2) — 봇 3 은 자동 ready 지만 사람 호스트가 준비 전이라 WAITING.
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fillWithBots").value(true))
                .andExpect(jsonPath("$.playerCount").value(4))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.botSeats.length()").value(3))
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(createdJson.get("botSeats").toString()).isEqualTo("[1,2,3]");
        String roomId = createdJson.get("roomId").asText();

        // 호스트가 준비하면 전원 ready → 게임 시작 (IN_GAME).
        mockMvc.perform(post("/api/rooms/" + roomId + "/ready")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_GAME"));
    }

    @Test
    void create_without_fillWithBots_stays_waiting_with_only_host() throws Exception {
        String token = registerAndLogin("default_user", "validpass1");

        var body = objectMapper.writeValueAsString(Map.of(
                "name", "친구 기다리는 방",
                "gameType", "TICHU"));
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fillWithBots").value(false))
                .andExpect(jsonPath("$.playerCount").value(1))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.botSeats.length()").value(0));
    }

    @Test
    void create_with_custom_target_score_persists_it() throws Exception {
        String token = registerAndLogin("target_user", "validpass1");

        var body = objectMapper.writeValueAsString(Map.of(
                "name", "300점 빠른 판",
                "gameType", "TICHU",
                "targetScore", 300));
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetScore").value(300));
    }

    @Test
    void create_with_turn_seconds_persists_it() throws Exception {
        String token = registerAndLogin("turn_user", "validpass1");

        var body = objectMapper.writeValueAsString(Map.of(
                "name", "30초 턴 방",
                "gameType", "TICHU",
                "turnSeconds", 30));
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turnSeconds").value(30));
    }

    @Test
    void create_without_turn_seconds_defaults_to_zero() throws Exception {
        String token = registerAndLogin("default_turn_user", "validpass1");

        var body = objectMapper.writeValueAsString(Map.of(
                "name", "턴 제한 끔 방",
                "gameType", "TICHU"));
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turnSeconds").value(0));
    }

    @Test
    void create_without_target_score_defaults_to_1000() throws Exception {
        String token = registerAndLogin("default_target_user", "validpass1");

        var body = objectMapper.writeValueAsString(Map.of(
                "name", "기본 목표 방",
                "gameType", "TICHU"));
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetScore").value(1000));
    }

    @Test
    void create_with_stake_persists_and_defaults_to_zero() throws Exception {
        String token = registerAndLogin("stake_user", "validpass1");
        MvcResult staked = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "내기 방", "gameType", "TICHU", "stake", 100))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stake").value(100))
                .andReturn();

        MvcResult plain = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "일반 방", "gameType", "TICHU"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stake").value(0))
                .andReturn();

        // 공유 Redis 오염 방지 — 생성한 방 정리(다른 테스트의 목록 가정 보호).
        leaveRoom(token, roomIdOf(staked));
        leaveRoom(token, roomIdOf(plain));
    }

    @Test
    void create_with_invalid_stake_returns_400() throws Exception {
        String token = registerAndLogin("bad_stake_user", "validpass1");
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "이상한 판돈", "gameType", "TICHU", "stake", 7))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_STAKE"));
    }

    @Test
    void create_staked_room_with_bots_returns_400() throws Exception {
        String token = registerAndLogin("stake_bot_user", "validpass1");
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "판돈+봇", "gameType", "TICHU",
                                "stake", 100, "fillWithBots", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("STAKED_ROOM_NO_BOTS"));
    }

    @Test
    void ready_in_staked_room_without_enough_chips_is_rejected() throws Exception {
        String token = registerAndLogin("broke_user", "validpass1");
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "고액 판돈", "gameType", "TICHU", "stake", 500))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stake").value(500))
                .andReturn();
        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        long hostId = json.get("hostId").asLong();
        String roomId = json.get("roomId").asText();

        // 잔액을 판돈(500)보다 낮춤: 1000 → 50. @Modifying 쿼리라 트랜잭션 안에서 실행.
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(s -> userRepo.decrementChipCapped(hostId, 950));

        mockMvc.perform(post("/api/rooms/" + roomId + "/ready")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ready\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CHIPS"));

        leaveRoom(token, roomId); // 공유 Redis 오염 방지.
    }

    private String roomIdOf(MvcResult created) throws Exception {
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("roomId").asText();
    }

    private void leaveRoom(String token, String roomId) throws Exception {
        mockMvc.perform(post("/api/rooms/" + roomId + "/leave")
                .header("Authorization", "Bearer " + token));
    }

    private String registerAndLogin(String username, String password) throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
