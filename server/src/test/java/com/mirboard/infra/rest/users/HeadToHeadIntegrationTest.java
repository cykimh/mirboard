package com.mirboard.infra.rest.users;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * D-154 — 상대 전적(GET /api/users/{id}/head-to-head). 기존 매치 기록에서 계산한다 — 티츄는 상대 팀만(내 팀 승패), 스컬킹은 최종 점수,
 * 원카드는 최종 순위로 사람 대 사람 승·패·무를 센다. 게스트·봇은 상대에서 뺀다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=headtohead-test-secret-must-be-32-bytes-or-more"
})
class HeadToHeadIntegrationTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;

    @Test
    void counts_wins_losses_draws_per_game_against_people_only() throws Exception {
        long me = register("h2h나");
        long amy = register("h2h에이미");
        long bob = register("h2h밥");
        long carl = register("h2h칼");
        long guest = users.save(User.create("guest-h2hzzzzz", GuestPolicy.NO_LOGIN_HASH, Clock.systemUTC())).getId();
        long bot = jdbc.queryForObject("SELECT id FROM users WHERE is_bot = true ORDER BY id LIMIT 1", Long.class);

        // 원카드 2판: 1판 나 1위·에이미 2위·게스트 3위 / 2판 에이미 1위·나 2위·봇 3위.
        oneCard(Map.of(me, 1, amy, 2, guest, 3));
        oneCard(Map.of(amy, 1, me, 2, bot, 3));
        // 스컬킹 1판: 나 80·밥 80(무)·에이미 20.
        skullKing(Map.of(me, 80, bob, 80, amy, 20));
        // 티츄 1판: 나+칼(A, 이김) 대 에이미+밥(B). 칼은 같은 팀이라 상대가 아니다.
        long tm = jdbc.queryForObject(
                "INSERT INTO tichu_match_results (room_id, team_a_score, team_b_score, payload_json) VALUES ('r', 1000, 200, '{}') RETURNING id",
                Long.class);
        for (long[] p : new long[][] {{me, 'A', 1}, {carl, 'A', 1}, {amy, 'B', 0}, {bob, 'B', 0}}) {
            jdbc.update("INSERT INTO tichu_match_participants (match_id, user_id, team, is_win) VALUES (?, ?, ?, ?)",
                    tm, p[0], String.valueOf((char) p[1]), p[2] == 1);
        }

        mockMvc.perform(get("/api/users/" + me + "/head-to-head").header("Authorization", "Bearer " + login("h2h나")))
                .andExpect(status().isOk())
                // 원카드 — 에이미와 1승 1패, 게스트·봇은 없다.
                .andExpect(jsonPath("$.entries[?(@.gameType == 'ONE_CARD' && @.opponentId == " + amy + ")].wins").value(1))
                .andExpect(jsonPath("$.entries[?(@.gameType == 'ONE_CARD' && @.opponentId == " + amy + ")].losses").value(1))
                .andExpect(jsonPath("$.entries[?(@.gameType == 'ONE_CARD' && @.opponentId == " + amy + ")].opponentName").value("h2h에이미"))
                .andExpect(jsonPath("$.entries[?(@.opponentId == " + guest + ")]").isEmpty())
                .andExpect(jsonPath("$.entries[?(@.opponentId == " + bot + ")]").isEmpty())
                // 스컬킹 — 밥과 무, 에이미에게 승.
                .andExpect(jsonPath("$.entries[?(@.gameType == 'SKULL_KING' && @.opponentId == " + bob + ")].draws").value(1))
                .andExpect(jsonPath("$.entries[?(@.gameType == 'SKULL_KING' && @.opponentId == " + amy + ")].wins").value(1))
                // 티츄 — 상대 팀 둘에게 승, 같은 팀 칼은 없다.
                .andExpect(jsonPath("$.entries[?(@.gameType == 'TICHU' && @.opponentId == " + bob + ")].wins").value(1))
                .andExpect(jsonPath("$.entries[?(@.gameType == 'TICHU' && @.opponentId == " + carl + ")]").isEmpty());

        // 게임을 고르면 그 게임만.
        mockMvc.perform(get("/api/users/" + me + "/head-to-head?gameType=ONE_CARD")
                        .header("Authorization", "Bearer " + login("h2h나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].opponentId").value(amy));
    }

    private void oneCard(Map<Long, Integer> ranks) {
        long id = jdbc.queryForObject(
                "INSERT INTO onecard_match_results (room_id, end_reason, payload_json) VALUES ('r', 'FINISHED', '{}') RETURNING id",
                Long.class);
        int seat = 0;
        for (var e : ranks.entrySet()) {
            jdbc.update("INSERT INTO onecard_match_participants (match_id, user_id, seat, final_rank, cards_left, is_win, deserted)"
                    + " VALUES (?, ?, ?, ?, 0, ?, false)", id, e.getKey(), seat++, e.getValue(), e.getValue() == 1);
        }
    }

    private void skullKing(Map<Long, Integer> scores) {
        long id = jdbc.queryForObject(
                "INSERT INTO skullking_match_results (room_id, rounds_played, payload_json) VALUES ('r', 10, '{}') RETURNING id",
                Long.class);
        int seat = 0;
        int best = scores.values().stream().max(Integer::compare).orElse(0);
        for (var e : scores.entrySet()) {
            jdbc.update("INSERT INTO skullking_match_participants (match_id, user_id, seat, final_score, is_win, deserted)"
                    + " VALUES (?, ?, ?, ?, ?, false)", id, e.getKey(), seat++, e.getValue(), e.getValue() == best);
        }
    }

    private long register(String username) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of("username", username, "password", "validpass1"));
        MvcResult res = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("userId").asLong();
    }

    private String login(String username) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of("username", username, "password", "validpass1"));
        MvcResult res = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
