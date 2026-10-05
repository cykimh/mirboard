package com.mirboard.domain.game.onecard.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 원카드 상태를 Redis 에 영속화한다 (D-128). 키는 티츄·스컬킹과 같은 {@code room:{id}:state} — 방당
 * 게임이 하나라 충돌이 없고, 방 소멸 정리 경로도 공유된다.
 *
 * <p>1판 = 1매치라 스컬킹의 매치 상태 키({@code match:{id}:state})가 없다. 손패도 상태 안에 있고,
 * resync 는 포트의 {@code privateView(state, seat)} 로 꺼낸다.
 */
@Repository
public class OneCardStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public OneCardStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, OneCardState state) {
        try {
            redis.opsForValue().set(stateKey(roomId), objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OneCardState for room " + roomId, e);
        }
    }

    public Optional<OneCardState> load(String roomId) {
        String json = redis.opsForValue().get(stateKey(roomId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, OneCardState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize OneCardState for room " + roomId, e);
        }
    }

    private static String stateKey(String roomId) {
        return "room:" + roomId + ":state";
    }
}
