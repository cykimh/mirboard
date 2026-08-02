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

    /** D-102 — 카탈로그 2게임 체제. 정렬은 status → displayName 이라 "스컬킹" 이 먼저다. */
    @Test
    void catalog_returns_both_games_when_authenticated() throws Exception {
        String token = authenticate();

        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games").isArray())
                .andExpect(jsonPath("$.games.length()").value(2))
                .andExpect(jsonPath("$.games[0].id").value("SKULL_KING"))
                .andExpect(jsonPath("$.games[0].displayName").value("스컬킹"))
                .andExpect(jsonPath("$.games[0].minPlayers").value(2))
                .andExpect(jsonPath("$.games[0].maxPlayers").value(8))
                // D-106 — 스컬킹은 10라운드 고정·개인전·칩 미지원이라 쓰는 옵션이 없다.
                .andExpect(jsonPath("$.games[0].supportedRoomOptions").isArray())
                .andExpect(jsonPath("$.games[0].supportedRoomOptions.length()").value(0))
                .andExpect(jsonPath("$.games[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.games[1].id").value("TICHU"))
                .andExpect(jsonPath("$.games[1].displayName").value("티츄"))
                .andExpect(jsonPath("$.games[1].minPlayers").value(4))
                .andExpect(jsonPath("$.games[1].maxPlayers").value(4))
                // D-106 — 배열 순서는 RoomOption 선언 순서다. 클라가 인덱스가 아니라
                // includes() 로 읽으므로 순서 자체가 기능은 아니지만, 카탈로그는 계약이라
                // 필드명·값 집합·순서가 말없이 바뀌면 클라가 조용히 어긋난다.
                .andExpect(jsonPath("$.games[1].supportedRoomOptions.length()").value(3))
                .andExpect(jsonPath("$.games[1].supportedRoomOptions[0]").value("TARGET_SCORE"))
                .andExpect(jsonPath("$.games[1].supportedRoomOptions[1]").value("TEAMS"))
                .andExpect(jsonPath("$.games[1].supportedRoomOptions[2]").value("BETTING"))
                .andExpect(jsonPath("$.games[1].status").value("AVAILABLE"));
    }

    @Test
    void single_game_lookup_by_id() throws Exception {
        String token = authenticate();

        mockMvc.perform(get("/api/games/TICHU").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("TICHU"))
                // D-106 — 목록과 단건이 같은 record 를 쓴다. 한쪽만 필드가 빠지는 회귀 방지.
                .andExpect(jsonPath("$.supportedRoomOptions.length()").value(3))
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
