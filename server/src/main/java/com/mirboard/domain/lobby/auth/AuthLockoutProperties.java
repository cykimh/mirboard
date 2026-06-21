package com.mirboard.domain.lobby.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-84 — 로그인 brute-force 잠금 파라미터. {@code window} 내 실패가 {@code maxFailures}
 * 이상이면 {@code lockDuration} 동안 잠근다. 전부 Redis TTL 로 표현(users 비침범).
 */
@ConfigurationProperties("mirboard.auth.lockout")
public record AuthLockoutProperties(int maxFailures, Duration window, Duration lockDuration) {

    public AuthLockoutProperties {
        if (maxFailures <= 0) maxFailures = 5;
        if (window == null) window = Duration.ofMinutes(15);
        if (lockDuration == null) lockDuration = Duration.ofMinutes(15);
    }
}
