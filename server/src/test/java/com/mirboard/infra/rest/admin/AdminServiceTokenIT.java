package com.mirboard.infra.rest.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirboard.domain.admin.AdminRole;
import com.mirboard.domain.admin.AdminRoleRepository;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/** D-170 — 허브 서비스 토큰으로 /api/admin/** 호출: 인증 분기와 admin_roles 게이트. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=admin-service-token-test-secret-must-be-32-bytes-or-more",
        "mirboard.admin.service-token=it-hub-service-token-0123456789abcdef",
        "mirboard.admin.service-user-id=9001"
})
class AdminServiceTokenIT {

    private static final String TOKEN = "it-hub-service-token-0123456789abcdef";
    private static final long SERVICE_USER_ID = 9001L;

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
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminRoleRepository adminRoleRepo;
    @Autowired Clock clock;

    /** 서비스 사용자(고정 id) 행 + 어드민 역할을 매 테스트 시작 시 복원한다. */
    @BeforeEach
    void seedServiceAdmin() {
        jdbc.update("INSERT INTO users (id, username, password_hash) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'hubsvc', '__no_login__') ON CONFLICT (id) DO NOTHING", SERVICE_USER_ID);
        adminRoleRepo.save(new AdminRole(SERVICE_USER_ID, clock.instant()));
    }

    @Test
    void correct_token_reads_chat_reports() throws Exception {
        mockMvc.perform(get("/api/admin/chat-reports")
                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports").isArray());
    }

    @Test
    void no_token_is_401() throws Exception {
        mockMvc.perform(get("/api/admin/chat-reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrong_token_is_401() throws Exception {
        mockMvc.perform(get("/api/admin/chat-reports")
                .header("Authorization", "Bearer " + TOKEN + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correct_token_is_not_accepted_outside_admin_paths() throws Exception {
        mockMvc.perform(get("/api/rooms")
                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void service_user_without_admin_role_is_403() throws Exception {
        adminRoleRepo.deleteById(SERVICE_USER_ID);

        mockMvc.perform(get("/api/admin/chat-reports")
                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
    }
}
