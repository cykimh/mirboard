package com.mirboard.infra.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
 * D-117 — 가입 없는 체험(`POST /api/auth/guest`) end-to-end.
 *
 * <p>방문자마다 별도 `users` 행이 생겨야 한다는 것이 이 결정의 핵심이다(공유 데모 계정은 좌석
 * 탈취·손패 유출). 그래서 "두 번 부르면 userId 가 다르다"를 먼저 본다. 그 밖에 게스트 규약
 * (로그인 불가·비번/아바타 금지), 전역 일일 상한(503 + Retry-After), 그리고 정리 작업이
 * 지울 수 없는 행(독 행)을 만나도 생성은 201 이라는 격리를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=guest-auth-test-secret-must-be-32-bytes-or-more",
        // 테스트마다 Redis 를 비우므로 테스트 하나 안에서 2회까지 — 상한 테스트가 3번째를 본다.
        "mirboard.guest.daily-cap=2"
})
class GuestAuthIntegrationTest {

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
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired StringRedisTemplate redis;

    private final SplittableRandom rng = new SplittableRandom();

    @BeforeEach
    void flushRedis() {
        // 일일 상한 카운터·정리 락/커서를 테스트마다 초기화.
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    private JsonNode createGuest() throws Exception {
        String json = mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    @Test
    void guest_gets_a_token_and_a_working_identity() throws Exception {
        JsonNode res = createGuest();

        assertThat(res.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(res.get("user").get("guest").asBoolean()).isTrue();
        String username = res.get("user").get("username").asText();
        assertThat(username).startsWith(GuestPolicy.PREFIX);

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + res.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.userId").value(res.get("user").get("userId").asLong()));
    }

    @Test
    void every_call_is_a_different_person() throws Exception {
        JsonNode first = createGuest();
        // 빈 본문도 Content-Type 만 JSON 이면 받는다.
        String json = mockMvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode second = objectMapper.readTree(json);

        assertThat(second.get("user").get("userId").asLong())
                .isNotEqualTo(first.get("user").get("userId").asLong());
        assertThat(second.get("user").get("username").asText())
                .isNotEqualTo(first.get("user").get("username").asText());
    }

    @Test
    void member_login_response_says_guest_false() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "member_g1", "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.guest").value(false));
    }

    @Test
    void guest_username_cannot_log_in_even_with_the_sentinel_as_password() throws Exception {
        String username = createGuest().get("user").get("username").asText();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", GuestPolicy.NO_LOGIN_HASH))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void guest_cannot_change_password_or_touch_avatar() throws Exception {
        String bearer = "Bearer " + createGuest().get("accessToken").asText();

        mockMvc.perform(put("/api/me/password").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("currentPassword", "whatever1", "newPassword", "newpass123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("GUEST_FORBIDDEN"));

        mockMvc.perform(multipart("/api/me/avatar")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1, 2, 3}))
                        .header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("GUEST_FORBIDDEN"));

        mockMvc.perform(delete("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("GUEST_FORBIDDEN"));
    }

    @Test
    void global_daily_cap_answers_503_with_retry_after() throws Exception {
        createGuest();
        createGuest();

        mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("GUEST_UNAVAILABLE"));
    }

    @Test
    void daily_counter_key_always_carries_its_ttl() throws Exception {
        // redis-keys.md 계약: `guest:issued:{UTC 날짜}` 는 TTL 48h. INCR 과 첫 EXPIRE 가 한
        // 스크립트라 "카운터가 있으면 TTL 도 있다"가 항상 참이어야 한다.
        createGuest();
        createGuest();

        String key = "guest:issued:" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        assertThat(redis.opsForValue().get(key)).isEqualTo("2");
        assertThat(redis.getExpire(key)).isBetween(1L, Duration.ofHours(48).toSeconds());
    }

    @Test
    void non_json_content_type_is_rejected() throws Exception {
        // consumes=JSON — 크로스사이트 simple request(text/plain 폼 전송)로는 만들 수 없다.
        mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void creation_still_succeeds_when_the_sweep_meets_a_row_it_cannot_delete() throws Exception {
        // 96h 전 게스트 둘: 하나는 참가 기록(participants)만 있어 FK 로 못 지우는 독 행 —
        // D-115 계약(비봇 참가자마다 user_game_stats)을 어긴 상태를 일부러 만든다. 다른
        // 하나는 정상 정리 대상. 생성 경로가 정리를 돌려도 201 이어야 하고, 독 행 하나
        // 때문에 정리 전체가 멈추면 안 된다.
        long poison = oldGuest();
        long stale = oldGuest();
        Long matchId = jdbc.queryForObject(
                "INSERT INTO tichu_match_results (room_id, team_a_score, team_b_score, payload_json)"
                        + " VALUES ('poison-room', 0, 0, '{}') RETURNING id", Long.class);
        jdbc.update("INSERT INTO tichu_match_participants (match_id, user_id, team, is_win)"
                + " VALUES (?, ?, 'A', false)", matchId, poison);

        createGuest();

        assertThat(users.existsById(poison)).isTrue();
        assertThat(users.existsById(stale)).isFalse();
    }

    private long oldGuest() {
        Clock past = Clock.fixed(clock.instant().minus(Duration.ofHours(96)), ZoneOffset.UTC);
        return users.save(User.create(GuestPolicy.newUsername(rng), GuestPolicy.NO_LOGIN_HASH, past))
                .getId();
    }
}
