package com.mirboard.domain.lobby.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RoomRepository {

    private static final String ROOMS_OPEN_KEY = "rooms:open";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> createScript;
    private final RedisScript<Long> joinScript;
    private final RedisScript<Long> leaveScript;

    public RoomRepository(
            StringRedisTemplate redis,
            @Qualifier("roomCreateScript") RedisScript<Long> createScript,
            @Qualifier("roomJoinScript") RedisScript<Long> joinScript,
            @Qualifier("roomLeaveScript") RedisScript<Long> leaveScript) {
        this.redis = redis;
        this.createScript = createScript;
        this.joinScript = joinScript;
        this.leaveScript = leaveScript;
    }

    public void create(String roomId, long hostUserId, String name, String gameType,
                       int capacity, long createdAt) {
        Long result = redis.execute(
                createScript,
                keysFor(roomId),
                roomId,
                Long.toString(hostUserId),
                name,
                gameType,
                Integer.toString(capacity),
                Long.toString(createdAt));
        if (result == null || result != 1L) {
            throw new IllegalStateException("room_create.lua returned unexpected: " + result);
        }
    }

    public int join(String roomId, long userId, long now) {
        Long result = redis.execute(
                joinScript,
                keysFor(roomId),
                Long.toString(userId),
                Long.toString(now),
                roomId);
        long v = unwrap(result);
        if (v == -1L) throw new RoomNotFoundException(roomId);
        if (v == -2L) throw new GameAlreadyStartedException(roomId);
        if (v == -3L) throw new RoomFullException(roomId);
        if (v == -4L) throw new AlreadyInRoomException(roomId);
        return (int) v;
    }

    public void leave(String roomId, long userId) {
        Long result = redis.execute(
                leaveScript,
                keysFor(roomId),
                Long.toString(userId),
                roomId);
        long v = unwrap(result);
        if (v == -1L) throw new RoomNotFoundException(roomId);
        if (v == -2L) throw new NotInRoomException(roomId);
        // v == 0 → room removed; v > 0 → remaining players. No further action needed.
    }

    public Optional<Room> findById(String roomId) {
        String roomKey = "room:" + roomId;
        Map<Object, Object> hash = redis.opsForHash().entries(roomKey);
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        List<String> playerStrings = redis.opsForList().range(roomKey + ":players", 0, -1);
        List<Long> playerIds = playerStrings == null
                ? List.of()
                : playerStrings.stream().map(Long::parseLong).toList();
        return Optional.of(new Room(
                roomId,
                (String) hash.get("name"),
                (String) hash.get("gameType"),
                Long.parseLong((String) hash.get("hostId")),
                RoomStatus.valueOf((String) hash.get("status")),
                Integer.parseInt((String) hash.get("capacity")),
                playerIds.size(),
                playerIds,
                Long.parseLong((String) hash.get("createdAt"))));
    }

    public List<String> openRoomIds() {
        Set<String> ids = redis.opsForZSet().range(ROOMS_OPEN_KEY, 0, -1);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    private static List<String> keysFor(String roomId) {
        return List.of(
                "room:" + roomId,
                "room:" + roomId + ":players",
                ROOMS_OPEN_KEY);
    }

    private static long unwrap(Long boxed) {
        return Objects.requireNonNull(boxed, "Lua script returned null").longValue();
    }
}
