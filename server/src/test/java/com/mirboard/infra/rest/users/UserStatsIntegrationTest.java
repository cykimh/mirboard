package com.mirboard.infra.rest.users;

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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 8D — GET /api/users/{id}/stats 검증. 신규 가입 사용자는 rating 1000 +
 * tier BRONZE.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=userstats-test-secret-must-be-32-bytes-or-more"
})
class UserStatsIntegrationTest {

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
    void new_user_starts_at_bronze_with_rating_1000() throws Exception {
        long userId = registerAndGetId("us_alice", "validpass1");
        String token = login("us_alice", "validpass1");

        mockMvc.perform(get("/api/users/" + userId + "/stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.username").value("us_alice"))
                .andExpect(jsonPath("$.winCount").value(0))
                .andExpect(jsonPath("$.loseCount").value(0))
                .andExpect(jsonPath("$.rating").value(1000))
                .andExpect(jsonPath("$.tier").value("BRONZE"));
    }

    @Test
    void unknown_user_returns_404() throws Exception {
        registerAndGetId("us_lookup", "validpass1");
        String token = login("us_lookup", "validpass1");

        mockMvc.perform(get("/api/users/999999/stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private long registerAndGetId(String username, String password) throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        MvcResult res = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("userId").asLong();
    }

    private String login(String username, String password) throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
