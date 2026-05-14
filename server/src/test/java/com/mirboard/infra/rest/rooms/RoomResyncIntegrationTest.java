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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=resync-test-secret-must-be-32-bytes-or-more"
})
class RoomResyncIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

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
    void resync_returns_tableView_and_privateHand_for_participant() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("rs_alice", "rs_bob", "rs_charlie", "rs_dave"));
        String roomId = createRoomAndJoinAll(tokens);

        String token = tokens.get("rs_alice");
        mockMvc.perform(get("/api/rooms/" + roomId + "/resync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.phase").value("PLAYING"))
                .andExpect(jsonPath("$.eventSeq").exists())
                .andExpect(jsonPath("$.tableView.handCounts.0").value(14))
                .andExpect(jsonPath("$.tableView.handCounts.3").value(14))
                .andExpect(jsonPath("$.tableView.currentTop").doesNotExist())
                .andExpect(jsonPath("$.privateHand.seat").value(0))
                .andExpect(jsonPath("$.privateHand.cards.length()").value(14));
    }

    @Test
    void resync_non_participant_is_409_not_in_room() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("rs2_alice", "rs2_bob", "rs2_charlie", "rs2_dave"));
        String roomId = createRoomAndJoinAll(tokens);

        // intruder is registered but not in the room
        String intruderToken = registerAndLogin("rs2_eve", "validpass1");

        mockMvc.perform(get("/api/rooms/" + roomId + "/resync")
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_IN_ROOM"));
    }

    @Test
    void resync_for_waiting_room_returns_404_not_available() throws Exception {
        String token = registerAndLogin("rs3_alice", "validpass1");
        String roomId = createRoomOnly(token);

        mockMvc.perform(get("/api/rooms/" + roomId + "/resync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESYNC_NOT_AVAILABLE"));
    }

    // ---------- helpers ----------

    private Map<String, String> registerAndLoginAll(List<String> usernames) throws Exception {
        // LinkedHashMap preserves insertion order → first key is the host (seat 0).
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

    private String createRoomOnly(String token) throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("name", "resync-room", "gameType", "TICHU"));
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
        String host = ordered.get(0);
        String roomId = createRoomOnly(tokens.get(host));
        for (int i = 1; i < ordered.size(); i++) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/join")
                            .header("Authorization", "Bearer " + tokens.get(ordered.get(i))))
                    .andExpect(status().isOk());
        }
        return roomId;
    }
}
