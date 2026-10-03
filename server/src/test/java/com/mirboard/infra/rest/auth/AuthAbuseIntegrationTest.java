package com.mirboard.infra.rest.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
 * D-84 — 로그인 brute-force 잠금(423) + 인증 IP 레이트리밋(429) end-to-end.
 * D-117 — 게스트 생성의 IP 일일 한도(`guest` 버킷)와 토큰 연쇄 우회 차단.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=auth-abuse-test-secret-must-be-32-bytes-or-more",
        "mirboard.auth.lockout.max-failures=3",
        "mirboard.auth.lockout.window=15m",
        "mirboard.auth.lockout.lock-duration=15m",
        // D-90 — 테스트는 기본 비활성이라 여기서 명시적으로 켠다. api-default 는 넉넉히
        // 둬서 auth 버킷만 시험한다.
        "mirboard.ratelimit.enabled=true",
        "mirboard.ratelimit.buckets.auth.limit=5",
        "mirboard.ratelimit.buckets.auth.window=1m",
        "mirboard.ratelimit.buckets.api-default.limit=1000",
        "mirboard.ratelimit.buckets.api-default.window=1m",
        "mirboard.ratelimit.buckets.guest.limit=2",
        "mirboard.ratelimit.buckets.guest.window=24h"
})
class AuthAbuseIntegrationTest {

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

    @Autowired
    RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void flushRedis() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    private String body(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    @Test
    void login_locks_account_after_max_failures() throws Exception {
        // 가입(요청 1)
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body("abuser01", "correctpass1"))).andExpect(status().isCreated());

        // 잘못된 비번 3회 → 각 401 (요청 2,3,4). 3번째에서 잠금 설정.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(body("abuser01", "wrongpass1")))
                    .andExpect(status().isUnauthorized());
        }

        // 4번째 시도(요청 5, 레이트리밋 5 이내): 잠금 상태 → 423 + ACCOUNT_LOCKED.
        // 올바른 비번이어도 잠겨 있으면 거부.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("abuser01", "correctpass1")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void auth_endpoint_rate_limited_per_ip() throws Exception {
        // 서로 다른 username 으로 5회(=limit) → 401(BAD_CREDENTIALS), 잠금은 트리거 안 됨.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(body("ghost" + i, "whatever1")))
                    .andExpect(status().isUnauthorized());
        }
        // 6번째(같은 IP) → 레이트리밋 초과 429.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("ghost9", "whatever1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void guest_creation_is_ip_rate_limited_even_with_tokens() throws Exception {
        String token = null;
        for (int i = 0; i < 2; i++) {
            String json = mockMvc.perform(post("/api/auth/guest")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            token = objectMapper.readTree(json).get("accessToken").asText();
        }
        // 같은 IP 3번째 → 하루 한도(2) 초과. 하루 윈도이므로 Retry-After 도 하루.
        mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "86400"))
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
        // 방금 받은 게스트 토큰을 실어도 userId 버킷으로 갈아타지 못한다(토큰 연쇄 차단).
        mockMvc.perform(post("/api/auth/guest")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
    }
}
