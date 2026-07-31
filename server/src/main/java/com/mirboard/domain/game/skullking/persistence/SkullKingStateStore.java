package com.mirboard.domain.game.skullking.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 스컬킹 라운드 상태를 Redis 에 영속화한다 (D-102). 키는 티츄와 같은
 * {@code room:{id}:state} — 방당 게임이 하나라 충돌이 없고, 방 소멸 정리 경로도 공유된다.
 *
 * <p>티츄와 달리 좌석별 hand 키가 없다 — 스컬킹 손패는 라운드 상태 안에 있고, resync 는
 * 포트의 {@code privateView(state, seat)} 로 꺼낸다.
 */
@Repository
public class SkullKingStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SkullKingStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, SkullKingState state) {
        try {
            redis.opsForValue().set(stateKey(roomId), objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize SkullKingState for room " + roomId, e);
        }
    }

    public Optional<SkullKingState> load(String roomId) {
        String json = redis.opsForValue().get(stateKey(roomId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SkullKingState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize SkullKingState for room " + roomId, e);
        }
    }

    private static String stateKey(String roomId) {
        return "room:" + roomId + ":state";
    }
}
