package com.mirboard.infra.ws;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 한 방의 게임 액션을 직렬화하는 짧은 TTL 락. Redis 단일 스레드 위에서 {@code SET NX EX}
 * 로 획득하고, 처리 후 명시 해제. 클라이언트의 비정상 종료에도 TTL 로 자동 만료.
 */
@Component
public class RoomActionLock {

    private static final Duration TTL = Duration.ofSeconds(2);

    private final StringRedisTemplate redis;

    public RoomActionLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String roomId) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key(roomId), "1", TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(String roomId) {
        redis.delete(key(roomId));
    }

    private static String key(String roomId) {
        return "room:" + roomId + ":lock";
    }
}
