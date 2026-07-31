package com.mirboard.domain.game.skullking.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 스컬킹 매치 누적 상태(라운드 번호·시작 좌석·누적 점수·탈주 좌석)를 Redis 에 영속화한다
 * (D-102). 키는 티츄와 같은 {@code match:{roomId}:state} — 방당 게임 하나 전제 공유.
 */
@Repository
public class SkullKingMatchStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SkullKingMatchStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, SkullKingMatchState state) {
        try {
            redis.opsForValue().set(key(roomId), objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize SkullKingMatchState for room " + roomId, e);
        }
    }

    public Optional<SkullKingMatchState> load(String roomId) {
        String json = redis.opsForValue().get(key(roomId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SkullKingMatchState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize SkullKingMatchState for room " + roomId, e);
        }
    }

    private static String key(String roomId) {
        return "match:" + roomId + ":state";
    }
}
