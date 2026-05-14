package com.mirboard.domain.game.tichu.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 마스터 게임 상태를 Redis 에 영속화한다. JSON 직렬화에는 sealed 계층에 붙은 Jackson
 * {@code @JsonTypeInfo} 어노테이션이 사용된다. 클라이언트는 본 저장소에 직접 접근하지
 * 못하고, 서버가 가공한 TableView/PrivateHand 만 본다 (state hiding 보증).
 */
@Repository
public class TichuGameStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TichuGameStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, TichuState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redis.opsForValue().set(stateKey(roomId), json, TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize TichuState for room " + roomId, e);
        }
    }

    public Optional<TichuState> load(String roomId) {
        String json = redis.opsForValue().get(stateKey(roomId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, TichuState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize TichuState for room " + roomId, e);
        }
    }

    public void saveHand(String roomId, long userId, List<Card> hand) {
        try {
            String json = objectMapper.writeValueAsString(hand);
            redis.opsForValue().set(handKey(roomId, userId), json, TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize hand", e);
        }
    }

    public Optional<List<Card>> loadHand(String roomId, long userId) {
        String json = redis.opsForValue().get(handKey(roomId, userId));
        if (json == null) return Optional.empty();
        try {
            Card[] arr = objectMapper.readValue(json, Card[].class);
            return Optional.of(List.of(arr));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize hand", e);
        }
    }

    public void deleteAll(String roomId, List<Long> userIds) {
        redis.delete(stateKey(roomId));
        for (Long uid : userIds) {
            redis.delete(handKey(roomId, uid));
        }
    }

    /** 마지막으로 발행된 이벤트의 seq. 한 번도 발행 전이면 0. */
    public long currentSeq(String roomId) {
        String val = redis.opsForValue().get("room:" + roomId + ":seq");
        return val == null ? 0L : Long.parseLong(val);
    }

    private static String stateKey(String roomId) {
        return "room:" + roomId + ":state";
    }

    private static String handKey(String roomId, long userId) {
        return "room:" + roomId + ":hand:" + userId;
    }
}
