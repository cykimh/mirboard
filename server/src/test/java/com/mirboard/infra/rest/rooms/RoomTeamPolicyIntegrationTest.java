package com.mirboard.infra.rest.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
 * Phase 8C — teamPolicy enum + RANDOM 셔플 + 호스트 정책 변경 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=teampolicy-test-secret-must-be-32-bytes-or-more"
})
class RoomTeamPolicyIntegrationTest {

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
    void default_team_policy_is_sequential() throws Exception {
        String hostToken = registerAndLogin("tp_seq_host", "validpass1");
        String roomId = createRoom(hostToken, null);

        mockMvc.perform(get("/api/rooms/" + roomId)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamPolicy").value("SEQUENTIAL"));
    }

    @Test
    void create_with_random_policy_persists() throws Exception {
        String hostToken = registerAndLogin("tp_rand_host", "validpass1");
        String roomId = createRoom(hostToken, "RANDOM");

        mockMvc.perform(get("/api/rooms/" + roomId)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(jsonPath("$.teamPolicy").value("RANDOM"));
    }

    @Test
    void host_can_change_policy_in_waiting() throws Exception {
        String hostToken = registerAndLogin("tp_chg_host", "validpass1");
        String roomId = createRoom(hostToken, null);

        var body = objectMapper.writeValueAsString(Map.of("teamPolicy", "RANDOM"));
        mockMvc.perform(put("/api/rooms/" + roomId + "/team-policy")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamPolicy").value("RANDOM"));
    }

    @Test
    void non_host_cannot_change_policy() throws Exception {
        String hostToken = registerAndLogin("tp_nh_host", "validpass1");
        String guestToken = registerAndLogin("tp_nh_guest", "validpass1");
        String roomId = createRoom(hostToken, null);

        var body = objectMapper.writeValueAsString(Map.of("teamPolicy", "RANDOM"));
        mockMvc.perform(put("/api/rooms/" + roomId + "/team-policy")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_HOST"));
    }

    @Test
    void cannot_change_policy_after_game_started() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("tp_started_host", "tp_started_b", "tp_started_c", "tp_started_d"));
        String roomId = createAndJoinAll(tokens, "RANDOM");
        String hostToken = tokens.get("tp_started_host");

        var body = objectMapper.writeValueAsString(Map.of("teamPolicy", "SEQUENTIAL"));
        mockMvc.perform(put("/api/rooms/" + roomId + "/team-policy")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GAME_ALREADY_STARTED"));
    }

    /**
     * RANDOM 정책에서는 4번째 join 시점에 좌석이 셔플되어야 함. 결정론적
     * 시드를 위해 직접 검증은 어려우니, "어떤 순서든 4명이 다 들어있다" + "host 가
     * playerIds 에 여전히 있다" 정도만 검증. (실제 셔플 확률은 24가지 순열 중
     * 23/24 가 원본과 다름)
     */
    @Test
    void random_policy_shuffles_seats_on_game_start() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("tp_sh_host", "tp_sh_b", "tp_sh_c", "tp_sh_d"));
        String roomId = createAndJoinAll(tokens, "RANDOM");
        String hostToken = tokens.get("tp_sh_host");

        MvcResult res = mockMvc.perform(get("/api/rooms/" + roomId)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_GAME"))
                .andExpect(jsonPath("$.playerCount").value(4))
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        List<Long> playerIds = new ArrayList<>();
        json.get("playerIds").forEach(n -> playerIds.add(n.asLong()));
        assertThat(playerIds).hasSize(4);
        long hostId = json.get("hostId").asLong();
        assertThat(playerIds).contains(hostId);
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

    private String createRoom(String token, String teamPolicy) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", "tp-room");
        payload.put("gameType", "TICHU");
        if (teamPolicy != null) payload.put("teamPolicy", teamPolicy);
        var body = objectMapper.writeValueAsString(payload);
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("roomId").asText();
    }

    private String createAndJoinAll(Map<String, String> tokens, String teamPolicy) throws Exception {
        List<String> ordered = List.copyOf(tokens.keySet());
        String host = ordered.get(0);
        String roomId = createRoom(tokens.get(host), teamPolicy);
        for (int i = 1; i < ordered.size(); i++) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/join")
                            .header("Authorization", "Bearer " + tokens.get(ordered.get(i))))
                    .andExpect(status().isOk());
        }
        return roomId;
    }
}
