package com.mirboard.infra.ws;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 방별 이벤트 시퀀스 카운터 (`room:{id}:seq`). 클라가 보낸 seq 는 무시하고 서버가 INCR
 * 결과를 단조 증가 카운터로 쓴다 (CLAUDE.md Server-Authoritative).
 *
 * <p>D-98: 발행({@link GameEventBroadcaster})과 조회(resync)가 각각 Redis 키를 직접
 * 만지고 있었고, 조회 쪽은 {@code TichuGameStateStore.currentSeq} 에 얹혀 있었다 —
 * 게임과 무관한 방 단위 관심사이므로 여기로 뽑았다.
 */
@Component
public class RoomSeq {

    private final StringRedisTemplate redis;

    public RoomSeq(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 다음 seq 발급 (INCR). */
    public long next(String roomId) {
        Long incremented = redis.opsForValue().increment(key(roomId));
        return incremented == null ? 0L : incremented;
    }

    /** 마지막으로 발행된 이벤트의 seq. 한 번도 발행 전이면 0. */
    public long current(String roomId) {
        String value = redis.opsForValue().get(key(roomId));
        return value == null ? 0L : Long.parseLong(value);
    }

    private static String key(String roomId) {
        return "room:" + roomId + ":seq";
    }
}
