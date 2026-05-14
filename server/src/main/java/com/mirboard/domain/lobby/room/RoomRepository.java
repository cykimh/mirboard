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
    private final RedisScript<Long> finishScript;

    public RoomRepository(
            StringRedisTemplate redis,
            @Qualifier("roomCreateScript") RedisScript<Long> createScript,
            @Qualifier("roomJoinScript") RedisScript<Long> joinScript,
            @Qualifier("roomLeaveScript") RedisScript<Long> leaveScript,
            @Qualifier("roomFinishScript") RedisScript<Long> finishScript) {
        this.redis = redis;
        this.createScript = createScript;
        this.joinScript = joinScript;
        this.leaveScript = leaveScript;
        this.finishScript = finishScript;
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

    public void markFinished(String roomId, long now) {
        Long result = redis.execute(
                finishScript,
                List.of("room:" + roomId, ROOMS_OPEN_KEY),
                roomId,
                Long.toString(now));
        long v = unwrap(result);
        if (v == -1L) throw new RoomNotFoundException(roomId);
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
        Set<String> spectatorStrings = redis.opsForSet().members(spectatorsKey(roomId));
        Set<Long> spectatorIds = spectatorStrings == null
                ? Set.of()
                : spectatorStrings.stream().map(Long::parseLong).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return Optional.of(new Room(
                roomId,
                (String) hash.get("name"),
                (String) hash.get("gameType"),
                Long.parseLong((String) hash.get("hostId")),
                RoomStatus.valueOf((String) hash.get("status")),
                Integer.parseInt((String) hash.get("capacity")),
                playerIds.size(),
                playerIds,
                spectatorIds,
                Long.parseLong((String) hash.get("createdAt"))));
    }

    /**
     * 관전자 추가. capacity 같은 원자성 제약이 없으므로 단순 SADD.
     * @return true 이면 새로 추가됨, false 이면 이미 관전자.
     */
    public boolean addSpectator(String roomId, long userId) {
        // Room 존재 확인 (Hash 비어있으면 NOT_FOUND).
        if (Boolean.FALSE.equals(redis.hasKey("room:" + roomId))) {
            throw new RoomNotFoundException(roomId);
        }
        Long added = redis.opsForSet().add(spectatorsKey(roomId), Long.toString(userId));
        redis.expire(spectatorsKey(roomId), java.time.Duration.ofHours(6));
        return added != null && added > 0;
    }

    /** 관전자 제거. 존재하지 않으면 no-op. */
    public boolean removeSpectator(String roomId, long userId) {
        Long removed = redis.opsForSet().remove(spectatorsKey(roomId), Long.toString(userId));
        return removed != null && removed > 0;
    }

    public boolean isSpectator(String roomId, long userId) {
        Boolean member = redis.opsForSet().isMember(spectatorsKey(roomId), Long.toString(userId));
        return Boolean.TRUE.equals(member);
    }

    private static String spectatorsKey(String roomId) {
        return "room:" + roomId + ":spectators";
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
