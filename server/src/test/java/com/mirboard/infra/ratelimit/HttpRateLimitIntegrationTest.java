package com.mirboard.infra.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.lobby.auth.JwtService;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>D-117 — 반대로 <b>인증 경로(`/api/auth/**`)는 언제나 IP 키</b>여야 하고, 그 IP 는 클라가
 * 위조할 수 있는 `X-Forwarded-For`·`Forwarded` 가 아니라 프록시가 채우는 신뢰 헤더
 * (`client-ip-header`, 운영 `Fly-Client-IP`)에서 온다. 두 결함이 실제로 뚫려 있었으므로
 * 각각을 공격 형태 그대로 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=ratelimit-http-test-secret-must-be-32-bytes-min",
        // 테스트는 기본 비활성이라 명시적으로 켠다. 셋업 가입/로그인은 매번 다른
        // Fly-Client-IP 로 보내 auth 버킷을 서로 깎지 않게 한다(tokenFor).
        "mirboard.ratelimit.enabled=true",
        "mirboard.ratelimit.client-ip-header=Fly-Client-IP",
        "mirboard.ratelimit.buckets.auth.limit=5",
        // 60 이 아닌 윈도 — Retry-After 가 고정 60 이 아니라 윈도에서 온다는 것을 가르기 위해.
        "mirboard.ratelimit.buckets.auth.window=2m",
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

    @Autowired
    JwtService jwtService;

    /** 셋업 요청마다 다른 클라 IP — 셋업이 시험 대상 버킷을 소모하지 않게. */
    private static final AtomicInteger SETUP_IP = new AtomicInteger(1);

    private static String setupIp() {
        return "198.51.100." + SETUP_IP.getAndIncrement();
    }

    @BeforeEach
    void flushRedis() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    /** 가입 + 로그인 → Bearer 토큰. */
    private String tokenFor(String username) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", "correctpass1"));
        mockMvc.perform(post("/api/auth/register").header("Fly-Client-IP", setupIp())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String json = mockMvc.perform(post("/api/auth/login").header("Fly-Client-IP", setupIp())
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

    private String badLogin(int i) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("username", "nobody" + i, "password", "whatever1"));
    }

    @Test
    void spoofed_forwarded_headers_do_not_reset_ip_bucket() throws Exception {
        // 공격: 매 요청 X-Forwarded-For·Forwarded 를 바꿔 다른 사람인 척. 예전에는
        // forward-headers-strategy 가 그 맨 왼쪽 값을 remoteAddr 로 삼아 매번 새 버킷이었다.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("Fly-Client-IP", "203.0.113.7")
                            .header("X-Forwarded-For", "10.0.0." + i)
                            .header("Forwarded", "for=10.0.1." + i)
                            .contentType(MediaType.APPLICATION_JSON).content(badLogin(i)))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login")
                        .header("Fly-Client-IP", "203.0.113.7")
                        .header("X-Forwarded-For", "10.0.0.99")
                        .header("Forwarded", "for=10.0.1.99")
                        .contentType(MediaType.APPLICATION_JSON).content(badLogin(99)))
                .andExpect(status().isTooManyRequests())
                // 고정 60 이 아니라 그 버킷의 윈도(2m) — 하루 윈도 버킷에 60 을 주면 클라가
                // 1분 뒤 재시도해 또 429 를 맞는다.
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void bearer_token_does_not_switch_auth_bucket_to_user_key() throws Exception {
        // 공격: 인증 경로에 매번 다른 유효 토큰을 실어 userId 버킷으로 갈아타기.
        // 토큰은 서명만 유효하면 되므로(필터가 DB 를 안 본다) 직접 발급한다.
        String invalidRegister = objectMapper.writeValueAsString(
                Map.of("username", "x", "password", "validpass1"));
        for (int i = 0; i < 5; i++) {
            String token = jwtService.issue(9000L + i, "tokenuser" + i).token();
            mockMvc.perform(post("/api/auth/register")
                            .header("Fly-Client-IP", "203.0.113.8")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(invalidRegister))
                    .andExpect(status().isBadRequest());
        }
        String another = jwtService.issue(9999L, "tokenuser99").token();
        mockMvc.perform(post("/api/auth/register")
                        .header("Fly-Client-IP", "203.0.113.8")
                        .header("Authorization", "Bearer " + another)
                        .contentType(MediaType.APPLICATION_JSON).content(invalidRegister))
                .andExpect(status().isTooManyRequests());
    }
}
