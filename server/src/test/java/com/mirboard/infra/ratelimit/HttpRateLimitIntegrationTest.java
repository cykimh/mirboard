package com.mirboard.infra.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * D-90 — 전역 HTTP 레이트리밋 end-to-end.
 *
 * <p>핵심 검증은 두 가지다. (1) 라우팅 표에 없는 평범한 `/api/**` 도 기본 버킷으로
 * 보호된다(route-drift 차단). (2) <b>키가 userId 라서 사용자끼리 서로의 할당량을
 * 깎지 않는다</b> — MockMvc 는 모든 요청이 같은 IP(127.0.0.1)라 IP 키였다면 두 번째
 * 사용자가 즉시 429 를 맞는다. 이게 D-90 의 NAT 오탐 회피 결정을 지키는 회귀 테스트다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=ratelimit-http-test-secret-must-be-32-bytes-min",
        // 테스트는 기본 비활성이라 명시적으로 켠다. 인증은 넉넉히(가입/로그인이 먼저
        // 막히면 본 시험을 못 한다), 기본 버킷만 조인다.
        "mirboard.ratelimit.enabled=true",
        "mirboard.ratelimit.buckets.auth.limit=1000",
        "mirboard.ratelimit.buckets.api-default.limit=5",
        "mirboard.ratelimit.buckets.api-default.window=1m"
})
class HttpRateLimitIntegrationTest {

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

    /** 가입 + 로그인 → Bearer 토큰. */
    private String tokenFor(String username) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", "correctpass1"));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String json = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("accessToken").asText();
    }

    @Test
    void unlisted_api_endpoints_are_covered_by_the_default_bucket() throws Exception {
        String token = tokenFor("rluser01");

        // 라우팅 표에 없는 평범한 조회 — 기본 버킷(5) 안에서는 통과.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        // 6번째 → 429 + 공통 에러 봉투 + Retry-After.
        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void one_users_limit_does_not_consume_anothers() throws Exception {
        String alice = tokenFor("rlalice1");
        String bob = tokenFor("rlbob0001");

        // alice 가 기본 버킷을 소진.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + alice))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + alice))
                .andExpect(status().isTooManyRequests());

        // bob 은 같은 IP(127.0.0.1) 지만 별도 키라 그대로 통과해야 한다.
        // 여기서 실패하면 키가 userId 가 아니라 IP 로 되돌아간 것 — D-90 회귀.
        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk());
    }
}
