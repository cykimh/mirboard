package com.mirboard.infra.ws;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 한 방의 게임 액션을 직렬화하는 짧은 TTL 락. Redis 단일 스레드 위에서 {@code SET NX EX}
 * 로 획득하고, 처리 후 명시 해제. 클라이언트의 비정상 종료에도 TTL 로 자동 만료.
 *
 * <p>D-140 — <b>내가 잡은 락만 푼다.</b> 획득 때 무작위 토큰을 값으로 넣고 해제는 토큰이 같을 때만 지운다
 * ({@code room_lock_release.lua}). 쥔 스레드가 TTL 보다 오래 멈췄다 돌아오면(매치 기록이 DB 장애로 선 경우 — F-server-2) 예전엔
 * 그사이 다른 경로가 잡은 락을 지웠다. 호출부는 모두 한 메서드 안에서 잡고 {@code finally} 로 풀므로 토큰은 잡은 스레드가 기억한다
 * — 다른 스레드의 해제는 남의 락이라 아무것도 안 한다.
 */
@Component
public class RoomActionLock {

    private static final Duration TTL = Duration.ofSeconds(2);

    /** {@link #acquireWaiting} 재시도 (TTL 2s 보다 넉넉히 — 가상스레드라 블로킹 저렴). */
    private static final int WAIT_RETRIES = 30;
    private static final long WAIT_RETRY_MILLIS = 100L;

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = releaseScript();

    private final StringRedisTemplate redis;
    /** 이 스레드가 잡은 방 → 그때 넣은 토큰. */
    private final ThreadLocal<Map<String, String>> held = ThreadLocal.withInitial(HashMap::new);

    public RoomActionLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String roomId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key(roomId), token, TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }
        held.get().put(roomId, token);
        return true;
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

    /** 이 스레드가 잡은 락이고 아직 내 것이면 지운다. 만료돼 남이 잡았거나 이 스레드가 잡지 않았으면 아무것도 안 한다. */
    public void release(String roomId) {
        Map<String, String> mine = held.get();
        String token = mine.remove(roomId);
        if (mine.isEmpty()) {
            held.remove();
        }
        if (token == null) {
            return;
        }
        redis.execute(RELEASE_SCRIPT, List.of(key(roomId)), token);
    }

    private static DefaultRedisScript<Long> releaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/room_lock_release.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static String key(String roomId) {
        return "room:" + roomId + ":lock";
    }
}
