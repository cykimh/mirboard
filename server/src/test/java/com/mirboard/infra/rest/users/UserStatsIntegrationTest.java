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

    @Autowired
    com.mirboard.domain.game.scoring.UserGameStatsService gameStats;

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

    @Test
    void ranking_excludes_bots_and_orders_by_rating_desc() throws Exception {
        long me = registerAndGetId("rk_user", "validpass1");
        String token = login("rk_user", "validpass1");
        // D-115 — 랭킹은 그 게임을 한 판이라도 한 사람만 싣는다.
        gameStats.record(me, "TICHU", true, 1020, false);

        mockMvc.perform(get("/api/users/ranking?limit=5")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                // 시드 봇(bot_*)은 랭킹에서 제외 — 어떤 entry 도 bot_ 로 시작하지 않음.
                .andExpect(jsonPath("$.entries[?(@.username =~ /bot_.*/)]").isEmpty())
                .andExpect(jsonPath("$.entries[0].rank").value(1));
    }

    // ---------- D-115 — 게임별 전적 ----------

    @Test
    void stats_lists_per_game_records_and_keeps_tichu_at_top_level() throws Exception {
        long userId = registerAndGetId("gs_multi", "validpass1");
        String token = login("gs_multi", "validpass1");
        gameStats.record(userId, "TICHU", true, 1020, false);
        gameStats.record(userId, "SKULL_KING", false, 985, true);

        mockMvc.perform(get("/api/users/" + userId + "/stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // 최상위 필드는 기존 클라 호환용 = TICHU.
                .andExpect(jsonPath("$.rating").value(1020))
                .andExpect(jsonPath("$.winCount").value(1))
                .andExpect(jsonPath("$.games.length()").value(2))
                .andExpect(jsonPath("$.games[?(@.gameType == 'SKULL_KING')].rating").value(985))
                .andExpect(jsonPath("$.games[?(@.gameType == 'SKULL_KING')].desertCount").value(1))
                .andExpect(jsonPath("$.games[?(@.gameType == 'SKULL_KING')].tier").value("BRONZE"));
    }

    @Test
    void ranking_is_per_game() throws Exception {
        long sk = registerAndGetId("gs_skonly", "validpass1");
        long tc = registerAndGetId("gs_tconly", "validpass1");
        String token = login("gs_skonly", "validpass1");
        gameStats.record(sk, "SKULL_KING", true, 1300, false);
        gameStats.record(tc, "TICHU", true, 1400, false);

        mockMvc.perform(get("/api/users/ranking?gameType=SKULL_KING&limit=100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameType").value("SKULL_KING"))
                .andExpect(jsonPath("$.entries[?(@.username == 'gs_skonly')].rating").value(1300))
                .andExpect(jsonPath("$.entries[?(@.username == 'gs_tconly')]").isEmpty());
    }

    @Test
    void ranking_for_unknown_game_is_404() throws Exception {
        registerAndGetId("gs_unknown", "validpass1");
        String token = login("gs_unknown", "validpass1");

        mockMvc.perform(get("/api/users/ranking?gameType=NOPE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void me_win_lose_is_the_sum_over_games() throws Exception {
        long userId = registerAndGetId("gs_me", "validpass1");
        String token = login("gs_me", "validpass1");
        gameStats.record(userId, "TICHU", true, null, false);
        gameStats.record(userId, "SKULL_KING", false, null, false);
        gameStats.record(userId, "SKULL_KING", true, null, false);

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winCount").value(2))
                .andExpect(jsonPath("$.loseCount").value(1));
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
