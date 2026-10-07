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
 * D-131 — 원카드를 다시 닫는 설정(`MIRBOARD_ONECARD_STATUS=COMING_SOON`, 운영 런북 `docs/deploy.md` 의 되돌리기)이 그대로
 * 듣는지 고정한다. 공개 전에는 기본값이 이 경로를 늘 지켰지만, 공개 뒤에는 설정을 준 이 테스트만 지킨다 — 카탈로그에는
 * "준비 중"으로 공개 게임 뒤에 오고, 방은 만들 수 없다(`RoomService` 가 AVAILABLE 만 허용).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=onecard-closed-test-secret-must-be-32-bytes-or-more",
        "mirboard.onecard.status=COMING_SOON"
})
class OneCardClosedIntegrationTest {

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
    void a_closed_one_card_is_listed_as_coming_soon_after_the_open_games() throws Exception {
        String token = authenticate("closed_catalog_u");

        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games.length()").value(3))
                .andExpect(jsonPath("$.games[0].id").value("SKULL_KING"))
                .andExpect(jsonPath("$.games[1].id").value("TICHU"))
                .andExpect(jsonPath("$.games[2].id").value("ONE_CARD"))
                .andExpect(jsonPath("$.games[2].status").value("COMING_SOON"));
    }

    @Test
    void a_closed_one_card_room_cannot_be_created() throws Exception {
        String token = authenticate("closed_room_u");

        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "원카드 방", "gameType", "ONE_CARD"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GAME_NOT_AVAILABLE"));
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
