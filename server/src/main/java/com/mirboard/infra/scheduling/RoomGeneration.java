package com.mirboard.infra.scheduling;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * D-96 — 방별 단조 증가 카운터. "그 사이 누가 행동했는가"를 인스턴스를 넘어 판정한다.
 *
 * <p>기존 {@code TurnTimeoutScheduler} 는 이걸 in-memory {@code AtomicLong} 으로 들고
 * 있었다. 프로세스 안에서만 유효하므로 2인스턴스에서는 <b>가드가 아예 작동하지 않는다</b> —
 * A 가 올린 generation 을 B 가 모르기 때문.
 *
 * <p>데드라인 member 에 이 값을 실어 보내고, 발화 시점에 현재 값과 비교해 다르면 버린다.
 * ZSET 원자 pop 이 "두 인스턴스가 같은 타이머를 잡는 것"을 막고, 이 카운터가 "pop 과
 * 락 획득 사이에 누가 행동한 것"을 막는다 — 두 방어가 서로 다른 경합을 담당한다.
 */
@Component
public class RoomGeneration {

    /** 방 TTL(6h)과 맞춤 — 방이 사라지면 카운터도 자연 소멸(구 in-memory 누수 해소). */
    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;

    public RoomGeneration(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 턴 진행 시 호출. 증가된 값을 반환한다. */
    public long bump(String roomId) {
        Long next = redis.opsForValue().increment(key(roomId));
        redis.expire(key(roomId), TTL);
        return next == null ? 0L : next;
    }

    /** 현재 값. 없으면 0. */
    public long current(String roomId) {
        String v = redis.opsForValue().get(key(roomId));
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 방 소멸 시 정리. TTL 이 있으니 필수는 아니지만 즉시 회수. */
    public void clear(String roomId) {
        redis.delete(key(roomId));
    }

    static String key(String roomId) {
        return "room:" + roomId + ":turngen";
    }
}
