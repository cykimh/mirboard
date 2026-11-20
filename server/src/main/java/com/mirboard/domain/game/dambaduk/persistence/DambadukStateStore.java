package com.mirboard.domain.game.dambaduk.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 담바둑 상태를 Redis 에 영속화한다. 키는 다른 게임과 같은 {@code room:{id}:state} — 방당 게임이 하나라 충돌이 없고 방 소멸 정리
 * 경로도 공유된다. 1판 = 1매치라 매치 상태 키가 따로 없다. 비공개 상태가 없어 이 값이 곧 공개 뷰의 원천이다.
 */
@Repository
public class DambadukStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DambadukStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, DambadukState state) {
        try {
            redis.opsForValue().set(stateKey(roomId), objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize DambadukState for room " + roomId, e);
        }
    }

    public Optional<DambadukState> load(String roomId) {
        String json = redis.opsForValue().get(stateKey(roomId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, DambadukState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize DambadukState for room " + roomId, e);
        }
    }

    private static String stateKey(String roomId) {
        return "room:" + roomId + ":state";
    }
}
