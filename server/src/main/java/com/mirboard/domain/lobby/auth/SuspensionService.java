package com.mirboard.domain.lobby.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * D-86 — 어드민이 부과하는 유저 정지. 정지 상태는 Redis(`suspend:user:{id}`, TTL)에만 둔다
 * (users 스키마 비침범, D-02 불변). 로그인/STOMP CONNECT 에서 차단한다.
 */
@Service
public class SuspensionService {

    private final StringRedisTemplate redis;

    public SuspensionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void suspend(long userId, Duration duration) {
        redis.opsForValue().set(key(userId), "1", duration);
    }

    public void unsuspend(long userId) {
        redis.delete(key(userId));
    }

    public boolean isSuspended(long userId) {
        return Boolean.TRUE.equals(redis.hasKey(key(userId)));
    }

    private static String key(long userId) {
        return "suspend:user:" + userId;
    }
}
