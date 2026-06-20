package com.mirboard.domain.lobby.room;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * D-82 — 방 단위 테이블 칩 스토어. `room:{id}:chips`(Redis HASH, userId→칩). 칩은 계정에
 * 누적되지 않고 방 안에서만 존재·이동하며, 방 소멸 시 TTL(6h)로 자연 정리된다. 게임 시작 시
 * 전원 동일 칩으로 초기화(이미 있으면 유지=리매치 누적), 매치 종료마다 RoomChipService 가
 * 갱신한다.
 */
@Repository
public class RoomChipStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;

    public RoomChipStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String roomId) {
        return "room:" + roomId + ":chips";
    }

    /** 현재 칩 스택(userId→칩). 미초기화 방은 빈 맵. */
    public Map<Long, Long> stacks(String roomId) {
        Map<Object, Object> hash = redis.opsForHash().entries(key(roomId));
        Map<Long, Long> out = new LinkedHashMap<>();
        hash.forEach((k, v) -> out.put(Long.parseLong((String) k), Long.parseLong((String) v)));
        return out;
    }

    /** 비어 있을 때만 전원 start 칩으로 초기화(리매치 시 기존 칩 유지). */
    public void initIfAbsent(String roomId, List<Long> userIds, long start) {
        String k = key(roomId);
        if (Boolean.TRUE.equals(redis.hasKey(k))) {
            return;
        }
        Map<String, String> init = new LinkedHashMap<>();
        for (Long uid : userIds) {
            init.put(uid.toString(), Long.toString(start));
        }
        redis.opsForHash().putAll(k, init);
        redis.expire(k, TTL);
    }

    /** 칩 스택 전체 덮어쓰기(정산/재바이인 결과 반영). */
    public void setStacks(String roomId, Map<Long, Long> stacks) {
        Map<String, String> m = new LinkedHashMap<>();
        stacks.forEach((uid, c) -> m.put(uid.toString(), Long.toString(c)));
        String k = key(roomId);
        redis.opsForHash().putAll(k, m);
        redis.expire(k, TTL);
    }

    /**
     * 판돈(threshold) 미만 보유자를 target 으로 무료 재바이인(프렌즈 모드). 리매치 새 매치
     * 시작 시 호출 — 빈털터리가 계속 참여할 수 있게 한다. 변경 없으면 no-op.
     */
    public void rebuyBelow(String roomId, List<Long> userIds, long threshold, long target) {
        Map<Long, Long> current = stacks(roomId);
        Map<Long, Long> updated = null;
        for (Long uid : userIds) {
            if (current.getOrDefault(uid, 0L) < threshold) {
                if (updated == null) {
                    updated = new LinkedHashMap<>(current);
                }
                updated.put(uid, target);
            }
        }
        if (updated != null) {
            setStacks(roomId, updated);
        }
    }

    public void delete(String roomId) {
        redis.delete(key(roomId));
    }
}
