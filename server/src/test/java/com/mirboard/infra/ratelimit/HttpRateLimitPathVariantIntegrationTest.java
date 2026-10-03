package com.mirboard.infra.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.lobby.auth.JwtService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-117 보정 — 같은 컨트롤러에 닿는 경로 변형이 레이트리밋만 비켜 가지 못하게.
 *
 * <p>필터가 날것의 {@code getRequestURI()} 로 판정하던 때는 `%67uest`·`%61pi` 같은 인코딩이나
 * `X-Forwarded-Prefix`(framework 전략이 contextPath 로 바꾼다)로 경로 문자열만 바꾸면, MVC·
 * Security 는 같은 엔드포인트로 매칭하는데 필터는 다른 버킷을 쓰거나 아예 건너뛰었다. 그러면
 * 게스트 IP 하루 한도를 우회해 전역 일일 상한을 혼자 소진할 수 있고, 인증 경로 IP 고정 키도
 * Bearer 를 실어 userId 키로 되돌릴 수 있다.
 *
 * <p>MockMvc 가 아니라 실제 Tomcat(RANDOM_PORT)과 경로를 다시 인코딩하지 않는 JDK HttpClient 를
 * 쓴다 — 원본 URI 처리·ForwardedHeaderFilter·방화벽이 운영과 같은 순서로 돌아야 재현된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=ratelimit-path-variant-secret-32-bytes-min",
        "mirboard.ratelimit.enabled=true",
        "mirboard.ratelimit.client-ip-header=Fly-Client-IP",
        "mirboard.ratelimit.buckets.guest.limit=2",
        "mirboard.ratelimit.buckets.guest.window=24h",
        "mirboard.ratelimit.buckets.auth.limit=3",
        "mirboard.ratelimit.buckets.auth.window=1m",
        "mirboard.ratelimit.buckets.api-default.limit=1000",
        "mirboard.ratelimit.buckets.api-default.window=1m"
})
class HttpRateLimitPathVariantIntegrationTest {

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

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JwtService jwtService;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    /** 경로를 그대로(재인코딩 없이) 보낸다. */
    private int post(String rawPath, String clientIp, Map<String, String> headers, String body)
            throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + rawPath))
                .header("Content-Type", "application/json")
                .header("Fly-Client-IP", clientIp)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(req::header);
        return http.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Set<String> rateLimitKeys() {
        return redis.keys("ratelimit:*");
    }

    static Stream<Arguments> guestPathVariants() {
        return Stream.of(
                Arguments.of("encoded last segment", "/api/auth/%67uest", Map.of()),
                Arguments.of("encoded first segment", "/%61pi/auth/guest", Map.of()),
                Arguments.of("forwarded prefix", "/api/auth/guest", Map.of("X-Forwarded-Prefix", "/zz")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guestPathVariants")
    void guest_path_variants_still_hit_the_guest_ip_bucket(
            String label, String rawPath, Map<String, String> headers) throws Exception {
        String ip = "203.0.113.21";
        // 한도 안에서는 게스트가 만들어진다 — 변형이 실제로 같은 컨트롤러에 닿는다는 증거.
        for (int i = 0; i < 2; i++) {
            assertThat(post(rawPath, ip, headers, "{}")).as("%s #%d", label, i + 1).isEqualTo(201);
        }
        assertThat(post(rawPath, ip, headers, "{}")).as("%s 한도 초과", label).isEqualTo(429);

        // auth(분당 20)나 api-default 가 아니라 하루 한도 버킷, 그리고 IP 키.
        assertThat(rateLimitKeys()).containsExactly("ratelimit:guest:ip:" + ip);
        assertThat(redis.opsForValue().get("ratelimit:guest:ip:" + ip)).isEqualTo("3");
    }

    @Test
    void encoded_auth_path_with_bearer_tokens_stays_on_the_auth_ip_bucket() throws Exception {
        String ip = "203.0.113.22";
        String invalidRegister = "{\"username\":\"x\",\"password\":\"validpass1\"}";
        // 매번 다른 유효 토큰 — userId 키로 갈아타면 요청마다 새 버킷이라 429 가 안 난다.
        for (int i = 0; i < 3; i++) {
            String token = jwtService.issue(9000L + i, "tokenuser" + i).token();
            assertThat(post("/api/%61uth/register", ip,
                    Map.of("Authorization", "Bearer " + token), invalidRegister))
                    .as("register #%d", i + 1).isEqualTo(400);
        }
        String another = jwtService.issue(9999L, "tokenuser99").token();
        assertThat(post("/api/%61uth/register", ip,
                Map.of("Authorization", "Bearer " + another), invalidRegister)).isEqualTo(429);

        assertThat(rateLimitKeys()).containsExactly("ratelimit:auth:ip:" + ip);
    }
}
