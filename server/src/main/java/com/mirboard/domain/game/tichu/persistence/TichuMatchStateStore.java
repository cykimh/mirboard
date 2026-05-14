package com.mirboard.domain.game.tichu.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 매치 누적 상태를 Redis 에 영속화. 키: {@code match:{roomId}:state}. 라운드 단위
 * {@link TichuGameStateStore} 와는 별도 저장 — 라운드가 갈려도 매치 상태는 유지된다.
 */
@Repository
public class TichuMatchStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TichuMatchStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, TichuMatchState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redis.opsForValue().set(key(roomId), json, TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize TichuMatchState", e);
        }
    }

    public Optional<TichuMatchState> load(String roomId) {
        String json = redis.opsForValue().get(key(roomId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, TichuMatchState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize TichuMatchState", e);
        }
    }

    public void delete(String roomId) {
        redis.delete(key(roomId));
    }

    private static String key(String roomId) {
        return "match:" + roomId + ":state";
    }
}
