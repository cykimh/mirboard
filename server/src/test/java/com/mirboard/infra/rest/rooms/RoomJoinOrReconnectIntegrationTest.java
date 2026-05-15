package com.mirboard.infra.rest.rooms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/**
 * Phase 8A — `/api/rooms/{id}/join-or-reconnect` + `/abort` 검증. 핵심 위험인 IN_GAME
 * 방 reconnect vs spectator 분기 (손패 노출 위험) 시나리오 포함.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=join-or-reconnect-test-secret-must-be-32-bytes-or-more"
})
class RoomJoinOrReconnectIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @Test
    void newcomer_to_waiting_room_is_joined() throws Exception {
        String hostToken = registerAndLogin("jor_host1", "validpass1");
        String guestToken = registerAndLogin("jor_guest1", "validpass1");
        String roomId = createRoom(hostToken);

        mockMvc.perform(post("/api/rooms/" + roomId + "/join-or-reconnect")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("JOINED"))
                .andExpect(jsonPath("$.room.playerCount").value(2))
                .andExpect(jsonPath("$.room.status").value("WAITING"));
    }

    @Test
    void existing_player_revisiting_waiting_room_is_reconnected_without_capacity_change()
            throws Exception {
        String hostToken = registerAndLogin("jor_host2", "validpass1");
        String roomId = createRoom(hostToken);

        // 호스트는 createRoom 으로 이미 playerIds[0] 위치. 다시 호출하면 RECONNECTED.
        mockMvc.perform(post("/api/rooms/" + roomId + "/join-or-reconnect")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RECONNECTED"))
                .andExpect(jsonPath("$.room.playerCount").value(1));
    }

    /**
     * 핵심 위험 시나리오 — IN_GAME 방에 비-참여자가 직접 링크로 들어와도 spectator
     * 로만 흡수. 절대 player 목록에 들어가면 안 됨 (손패 노출 방지).
     */
    @Test
    void newcomer_to_in_game_room_is_forced_to_spectate() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("jor_a", "jor_b", "jor_c", "jor_d"));
        String roomId = createRoomAndJoinAll(tokens);  // 4명 다 join → IN_GAME

        String outsiderToken = registerAndLogin("jor_outsider", "validpass1");
        mockMvc.perform(post("/api/rooms/" + roomId + "/join-or-reconnect")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SPECTATING"))
                .andExpect(jsonPath("$.room.playerCount").value(4));

        // 손패 노출 0건 검증 — resync 의 privateHand 가 null 이어야 함.
        mockMvc.perform(get("/api/rooms/" + roomId + "/resync")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privateHand").doesNotExist());
    }

    /**
     * IN_GAME 방에서 원래 플레이어가 다시 호출하면 RECONNECTED — 좌석은 보존되고
     * 손패는 resync 로 그대로 받을 수 있어야 함.
     */
    @Test
    void original_player_revisiting_in_game_room_is_reconnected() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("jor_p1", "jor_p2", "jor_p3", "jor_p4"));
        String roomId = createRoomAndJoinAll(tokens);

        String aliceToken = tokens.get("jor_p1");
        mockMvc.perform(post("/api/rooms/" + roomId + "/join-or-reconnect")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RECONNECTED"))
                .andExpect(jsonPath("$.room.playerCount").value(4));

        // 본인 손패는 정상 노출.
        mockMvc.perform(get("/api/rooms/" + roomId + "/resync")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privateHand").exists());
    }

    @Test
    void host_can_abort_in_game_room() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("jor_h_host", "jor_h2", "jor_h3", "jor_h4"));
        String roomId = createRoomAndJoinAll(tokens);
        String hostToken = tokens.get("jor_h_host");

        mockMvc.perform(post("/api/rooms/" + roomId + "/abort")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/rooms/" + roomId)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"));
    }

    @Test
    void non_host_cannot_abort() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("jor_nh_host", "jor_nh2", "jor_nh3", "jor_nh4"));
        String roomId = createRoomAndJoinAll(tokens);
        String otherToken = tokens.get("jor_nh2");

        mockMvc.perform(post("/api/rooms/" + roomId + "/abort")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_HOST"));
    }

    @Test
    void cannot_abort_waiting_room() throws Exception {
        String hostToken = registerAndLogin("jor_w_host", "validpass1");
        String roomId = createRoom(hostToken);

        mockMvc.perform(post("/api/rooms/" + roomId + "/abort")
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GAME_NOT_IN_PROGRESS"));
    }

    // ---------- helpers ----------

    private Map<String, String> registerAndLoginAll(List<String> usernames) throws Exception {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String u : usernames) {
            tokens.put(u, registerAndLogin(u, "validpass1"));
        }
        return tokens;
    }

    private String registerAndLogin(String username, String password) throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String createRoom(String token) throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("name", "jor-room", "gameType", "TICHU"));
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        return json.get("roomId").asText();
    }

    private String createRoomAndJoinAll(Map<String, String> tokens) throws Exception {
        List<String> ordered = List.copyOf(tokens.keySet());
        String hostUser = ordered.get(0);
        String roomId = createRoom(tokens.get(hostUser));
        for (int i = 1; i < ordered.size(); i++) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/join")
                            .header("Authorization", "Bearer " + tokens.get(ordered.get(i))))
                    .andExpect(status().isOk());
        }
        return roomId;
    }
}
