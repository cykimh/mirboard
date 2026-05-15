package com.mirboard.infra.rest.games;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=catalog-test-secret-must-be-32-bytes-or-more"
})
class GameCatalogIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> MYSQL = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void catalog_requires_authentication() throws Exception {
        mockMvc.perform(get("/api/games"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void catalog_returns_tichu_as_available_when_authenticated() throws Exception {
        String token = authenticate();

        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games").isArray())
                .andExpect(jsonPath("$.games[0].id").value("TICHU"))
                .andExpect(jsonPath("$.games[0].displayName").value("티츄"))
                .andExpect(jsonPath("$.games[0].minPlayers").value(4))
                .andExpect(jsonPath("$.games[0].maxPlayers").value(4))
                .andExpect(jsonPath("$.games[0].status").value("AVAILABLE"));
    }

    @Test
    void single_game_lookup_by_id() throws Exception {
        String token = authenticate();

        mockMvc.perform(get("/api/games/TICHU").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("TICHU"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void unknown_game_returns_404_with_code() throws Exception {
        String token = authenticate();

        mockMvc.perform(get("/api/games/NONEXISTENT").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GAME_NOT_AVAILABLE"));
    }

    private String authenticate() throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("username", "catalog_u", "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }
}
