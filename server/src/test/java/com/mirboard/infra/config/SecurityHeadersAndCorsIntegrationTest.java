package com.mirboard.infra.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-83 — CORS origin 화이트리스트 + 보안 헤더. 전면 개방(`*`) 폐지 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=cors-headers-test-secret-must-be-32-bytes-or-more",
        "mirboard.security.allowed-origins=http://localhost:5173,http://localhost:8080"
})
class SecurityHeadersAndCorsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    // D-117 — 게스트 생성은 Redis(일일 상한)를 거친다. 컨테이너가 없으면 허용 origin 쪽도
    // 503 이 나서 "외부 origin 이라 안 만들어졌다"를 가를 수 없다.
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
    JdbcTemplate jdbc;

    private int guestRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE 'guest-%'", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void responses_carry_security_headers() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void cors_preflight_from_allowed_origin_is_accepted() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void cors_preflight_from_disallowed_origin_is_rejected() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void guest_creation_rejects_foreign_origin() throws Exception {
        // D-117 — 남의 사이트가 방문자 브라우저로 게스트를 찍어내지 못한다(CORS 403 + consumes=JSON
        // 이라 프리플라이트가 강제됨). 허용 origin 대조군으로 "막혀서 안 생겼다"를 가른다.
        int before = guestRows();

        mockMvc.perform(post("/api/auth/guest")
                        .header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(guestRows()).isEqualTo(before);

        mockMvc.perform(post("/api/auth/guest")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        assertThat(guestRows()).isEqualTo(before + 1);
    }
}
