package com.mirboard.infra.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-84 — 인증 엔드포인트 IP 고정 윈도 레이트리밋 파라미터. {@code window} 동안 IP 당
 * 최대 {@code limit} 요청. (토큰버킷·전역 필터 추상화는 M2.)
 */
@ConfigurationProperties("mirboard.ratelimit.auth")
public record AuthRateLimitProperties(int limit, Duration window) {

    public AuthRateLimitProperties {
        if (limit <= 0) limit = 20;
        if (window == null) window = Duration.ofMinutes(1);
    }
}
