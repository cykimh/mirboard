package com.mirboard.infra.rest.games;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-168 — 당분간 원카드만 연다(운영 `fly.toml` 의 `MIRBOARD_TICHU_STATUS`·`MIRBOARD_SKULLKING_STATUS=COMING_SOON`). 그 설정이
 * 그대로 듣는지 고정한다 — 티츄·스컬킹은 카탈로그에 "준비 중"으로 원카드 뒤에 오고, 방은 만들 수 없다. 원카드는 그대로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=onecard-only-test-secret-must-be-32-bytes-or-more",
        "mirboard.tichu.status=COMING_SOON",
        "mirboard.skullking.status=COMING_SOON"
})
class OnlyOneCardOpenIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

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
    void tichu_and_skull_king_are_listed_as_coming_soon_after_one_card() throws Exception {
        String token = authenticate("onlyoc1");

        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games.length()").value(3))
                .andExpect(jsonPath("$.games[0].id").value("ONE_CARD"))
                .andExpect(jsonPath("$.games[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.games[1].status").value("COMING_SOON"))
                .andExpect(jsonPath("$.games[2].status").value("COMING_SOON"));
    }

    @Test
    void only_one_card_rooms_can_be_created() throws Exception {
        String token = authenticate("onlyoc2");

        for (String game : java.util.List.of("TICHU", "SKULL_KING")) {
            mockMvc.perform(post("/api/rooms")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "방", "gameType", game))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("GAME_NOT_AVAILABLE"));
        }
        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "원카드 방", "gameType", "ONE_CARD"))))
                .andExpect(status().isCreated());
    }

    private String authenticate(String username) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of("username", username, "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
