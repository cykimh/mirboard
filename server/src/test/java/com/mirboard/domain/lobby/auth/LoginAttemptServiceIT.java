package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-84 — 로그인 실패 누적 잠금(Redis 전용). users 스키마 비침범.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=login-attempt-test-secret-must-be-32-bytes-or-more",
        "mirboard.auth.lockout.max-failures=3",
        "mirboard.auth.lockout.window=15m",
        "mirboard.auth.lockout.lock-duration=15m"
})
class LoginAttemptServiceIT {

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
    LoginAttemptService service;

    @Test
    void locks_after_max_failures() {
        String user = "lockme";
        assertThatNoException().isThrownBy(() -> service.assertNotLocked(user));

        service.onFailure(user);
        service.onFailure(user);
        // 2 < 3 → 아직 잠금 아님
        assertThatNoException().isThrownBy(() -> service.assertNotLocked(user));

        service.onFailure(user); // 3번째 → 잠금
        assertThatThrownBy(() -> service.assertNotLocked(user))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void success_resets_failure_count() {
        String user = "resetme";
        service.onFailure(user);
        service.onFailure(user);
        service.onSuccess(user); // 카운터/잠금 해제

        service.onFailure(user); // 1
        service.onFailure(user); // 2 (리셋되었으므로 < 3)
        assertThatNoException().isThrownBy(() -> service.assertNotLocked(user));
    }
}
