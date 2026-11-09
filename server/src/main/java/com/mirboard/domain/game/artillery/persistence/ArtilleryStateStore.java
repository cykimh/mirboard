package com.mirboard.domain.game.artillery.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 씨앗포 상태를 Redis 에 영속화한다 (S2). 키는 다른 게임과 같은 {@code room:{id}:state} — 방당 게임이 하나라 충돌이 없다.
 * 지형은 저장하지 않으므로(시드 + 구멍 목록 — D-220) 값이 작다.
 */
@Repository
public class ArtilleryStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ArtilleryStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, ArtilleryState state) {
        try {
            redis.opsForValue().set(stateKey(roomId), objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ArtilleryState for room " + roomId, e);
        }
    }

    public Optional<ArtilleryState> load(String roomId) {
        String json = redis.opsForValue().get(stateKey(roomId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ArtilleryState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize ArtilleryState for room " + roomId, e);
        }
    }

    private static String stateKey(String roomId) {
        return "room:" + roomId + ":state";
    }
}
