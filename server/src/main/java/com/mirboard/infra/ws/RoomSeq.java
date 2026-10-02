package com.mirboard.infra.ws;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 방별 이벤트 시퀀스 카운터 (`room:{id}:seq`). 클라가 보낸 seq 는 무시하고 서버가 INCR
 * 결과를 단조 증가 카운터로 쓴다 (CLAUDE.md Server-Authoritative).
 *
 * <p>D-98: 발행({@link GameEventBroadcaster})과 조회(resync)가 각각 Redis 키를 직접
 * 만지고 있었고, 조회 쪽은 {@code TichuGameStateStore.currentSeq} 에 얹혀 있었다 —
 * 게임과 무관한 방 단위 관심사이므로 여기로 뽑았다.
 *
 * <p><b>TTL</b>: 발급은 INCR 이 아니라 INCR+EXPIRE 를 묶은 Lua 다. 순수 INCR 이던
 * 시절엔 이 키만 TTL 없이 남아 방 하나당 고아 카운터가 하나씩 영구 적립됐다
 * (`docs/redis-keys.md` 는 처음부터 6h 로 적혀 있었다). 정리 Lua 로 지우지 않고 TTL 로
 * 사그라들게 두는 건 의도적이다 — 방이 살아있는데 키가 사라지면 INCR 이 1부터 다시
 * 시작해 클라의 seq gap 판정이 깨지므로, 발행 때마다 TTL 을 갱신해 활동 중인 방에서는
 * 절대 만료되지 않게 한다.
 */
@Component
public class RoomSeq {

    /** 다른 room 키와 동일 (`docs/redis-keys.md`). 발행 때마다 갱신되는 슬라이딩 TTL. */
    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> nextScript;

    public RoomSeq(StringRedisTemplate redis,
                   @Qualifier("roomSeqNextScript") RedisScript<Long> nextScript) {
        this.redis = redis;
        this.nextScript = nextScript;
    }

    /** 다음 seq 발급 (INCR + TTL 갱신). */
    public long next(String roomId) {
        Long incremented = redis.execute(nextScript, List.of(key(roomId)),
                String.valueOf(TTL.toSeconds()));
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
