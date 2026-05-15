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
