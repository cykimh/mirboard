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

    /** {@link #acquireWaiting} 재시도 (TTL 2s 보다 넉넉히 — 가상스레드라 블로킹 저렴). */
    private static final int WAIT_RETRIES = 30;
    private static final long WAIT_RETRY_MILLIS = 100L;

    private final StringRedisTemplate redis;

    public RoomActionLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String roomId) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key(roomId), "1", TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 기다려서라도 잡아야 하는 쪽(탈주 확정·강제 종료)의 획득 — 액션 1건이 끝날 때까지 짧게
     * 재시도한다(최대 약 3초, 락 TTL 2초보다 길다). 액션 경로(STOMP·봇·타임아웃)는 기다리지 않고
     * {@link #tryAcquire} 로 BUSY/재스케줄한다.
     *
     * @return 잡았으면 true. 인터럽트·재시도 소진이면 false
     */
    public boolean acquireWaiting(String roomId) {
        for (int i = 0; i < WAIT_RETRIES; i++) {
            if (tryAcquire(roomId)) {
                return true;
            }
            try {
                Thread.sleep(WAIT_RETRY_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public void release(String roomId) {
        redis.delete(key(roomId));
    }

    private static String key(String roomId) {
        return "room:" + roomId + ":lock";
    }
}
