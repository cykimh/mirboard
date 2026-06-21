package com.mirboard.infra.ratelimit;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * D-84 — 인증 IP 고정 윈도 레이트리밋 (Redis Lua 원자 INCR+EXPIRE).
 * 토큰버킷·전역 HTTP/STOMP 필터로의 일반화는 M2(C1 완성).
 */
@Component
public class AuthRateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;
    private final AuthRateLimitProperties props;

    public AuthRateLimiter(StringRedisTemplate redis,
                           @Qualifier("rateLimitFixedWindowScript") RedisScript<Long> script,
                           AuthRateLimitProperties props) {
        this.redis = redis;
        this.script = script;
        this.props = props;
    }

    /** @return true 면 허용, false 면 윈도 한도 초과. */
    public boolean tryAcquire(String clientIp) {
        long windowSeconds = Math.max(1L, props.window().toSeconds());
        Long allowed = redis.execute(script, List.of(key(clientIp)),
                Integer.toString(props.limit()), Long.toString(windowSeconds));
        return allowed != null && allowed == 1L;
    }

    private static String key(String clientIp) {
        return "ratelimit:auth:ip:" + clientIp;
    }
}
