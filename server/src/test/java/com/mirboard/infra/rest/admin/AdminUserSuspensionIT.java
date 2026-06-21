package com.mirboard.infra.rest.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.admin.AdminRole;
import com.mirboard.domain.admin.AdminRoleRepository;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** D-86 — 어드민 유저 정지: 로그인 차단(403)·해제·권한 게이트. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=admin-suspend-test-secret-must-be-32-bytes-or-more"
})
class AdminUserSuspensionIT {

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
    @Autowired UserRepository userRepo;
    @Autowired AdminRoleRepository adminRoleRepo;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        return userRepo.save(User.create(prefix + SEQ.incrementAndGet(),
                passwordEncoder.encode("validpass1"), clock));
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.issue(u.getId(), u.getUsername()).token();
    }

    private int login(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("username", username, "password", "validpass1"))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void admin_suspends_and_lifts_user_login() throws Exception {
        User admin = newUser("su_admin");
        adminRoleRepo.save(new AdminRole(admin.getId(), clock.instant()));
        User target = newUser("su_target");

        // 정지 전 로그인 OK.
        org.assertj.core.api.Assertions.assertThat(login(target.getUsername())).isEqualTo(200);

        // 어드민이 정지 → 로그인 403 ACCOUNT_SUSPENDED.
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/suspend")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"minutes\":60}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("username", target.getUsername(), "password", "validpass1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));

        // 해제 → 로그인 다시 OK.
        mockMvc.perform(delete("/api/admin/users/" + target.getId() + "/suspend")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(login(target.getUsername())).isEqualTo(200);
    }

    @Test
    void non_admin_cannot_suspend() throws Exception {
        User notAdmin = newUser("su_plain");
        User target = newUser("su_victim");
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/suspend")
                .header("Authorization", bearer(notAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"minutes\":60}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
    }
}
