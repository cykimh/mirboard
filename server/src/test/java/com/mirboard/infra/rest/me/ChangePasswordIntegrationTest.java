package com.mirboard.infra.rest.me;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** D-85 — 본인 비밀번호 변경 (PUT /api/me/password). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=change-password-test-secret-must-be-32-bytes-or-more"
})
class ChangePasswordIntegrationTest {

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

    private String body(Map<String, String> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("username", username, "password", password))))
                .andExpect(status().isCreated());
        var res = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return node.get("accessToken").asText();
    }

    @Test
    void change_password_then_login_with_new_password() throws Exception {
        String token = registerAndLogin("pw_changer", "oldpass12");

        mockMvc.perform(put("/api/me/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("currentPassword", "oldpass12", "newPassword", "newpass34"))))
                .andExpect(status().isNoContent());

        // 새 비번 로그인 성공.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("username", "pw_changer", "password", "newpass34"))))
                .andExpect(status().isOk());
        // 옛 비번 로그인 실패.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("username", "pw_changer", "password", "oldpass12"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrong_current_password_is_rejected() throws Exception {
        String token = registerAndLogin("pw_wrongcur", "oldpass12");
        mockMvc.perform(put("/api/me/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("currentPassword", "WRONGcur99", "newPassword", "newpass34"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void weak_new_password_is_rejected() throws Exception {
        String token = registerAndLogin("pw_weaknew", "oldpass12");
        mockMvc.perform(put("/api/me/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("currentPassword", "oldpass12", "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
