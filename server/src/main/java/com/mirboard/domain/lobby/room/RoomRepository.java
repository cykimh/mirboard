package com.mirboard.domain.lobby.room;

import com.mirboard.domain.lobby.auth.BotUserRegistry;
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
    private final RedisScript<Long> readyScript;
    private final RedisScript<Long> deleteScript;
    private final RedisScript<Long> startNowScript;
    private final RedisScript<Long> returnWaitingScript;
    private final BotUserRegistry bots;

    public RoomRepository(
            StringRedisTemplate redis,
            @Qualifier("roomCreateScript") RedisScript<Long> createScript,
            @Qualifier("roomJoinScript") RedisScript<Long> joinScript,
            @Qualifier("roomLeaveScript") RedisScript<Long> leaveScript,
            @Qualifier("roomFinishScript") RedisScript<Long> finishScript,
            @Qualifier("roomReadyScript") RedisScript<Long> readyScript,
            @Qualifier("roomDeleteScript") RedisScript<Long> deleteScript,
            @Qualifier("roomStartNowScript") RedisScript<Long> startNowScript,
            @Qualifier("roomReturnWaitingScript") RedisScript<Long> returnWaitingScript,
            BotUserRegistry bots) {
        this.redis = redis;
        this.createScript = createScript;
        this.joinScript = joinScript;
        this.leaveScript = leaveScript;
        this.finishScript = finishScript;
        this.readyScript = readyScript;
        this.deleteScript = deleteScript;
        this.startNowScript = startNowScript;
        this.returnWaitingScript = returnWaitingScript;
        this.bots = bots;
    }

    /**
     * Phase 19(#1, D-75) — 방 무조건 소멸 (room_delete.lua). 호출 측이
     * "플레이어 0 && 관전자 0" 을 보장해야 한다.
     *
     * @return 방이 존재해서 삭제됐으면 true.
     */
    public boolean deleteRoom(String roomId) {
        Long result = redis.execute(
                deleteScript,
                List.of(
                        "room:" + roomId,
                        "room:" + roomId + ":players",
                        readyKey(roomId),
                        spectatorsKey(roomId),
                        ROOMS_OPEN_KEY),
                roomId);
        return result != null && result == 1L;
    }

    public void create(String roomId, long hostUserId, String name, String gameType,
                       int capacity, long createdAt, TeamPolicy teamPolicy,
                       boolean fillWithBots, int targetScore, int turnSeconds, int stake) {
        Long result = redis.execute(
                createScript,
                keysFor(roomId),
                roomId,
                Long.toString(hostUserId),
                name,
                gameType,
                Integer.toString(capacity),
                Long.toString(createdAt),
                teamPolicy.name(),
                Boolean.toString(fillWithBots),
                Integer.toString(targetScore),
                Integer.toString(turnSeconds),
                Integer.toString(stake));
        if (result == null || result != 1L) {
            throw new IllegalStateException("room_create.lua returned unexpected: " + result);
        }
    }

    /**
     * Phase 8C — IN_GAME 전이 직후 RANDOM 정책에서 좌석 셔플용. capacity 가 이미
     * 막혔으므로 신규 join 동시성 위험 없음. DEL + RPUSHALL 2-step (Lua 미사용).
     */
    public void replacePlayerOrder(String roomId, List<Long> newOrder) {
        String key = "room:" + roomId + ":players";
        redis.delete(key);
        String[] payload = newOrder.stream().map(String::valueOf).toArray(String[]::new);
        redis.opsForList().rightPushAll(key, payload);
        redis.expire(key, java.time.Duration.ofHours(6));
    }

    /** Phase 8C — 호스트가 WAITING 중 정책을 변경. status=IN_GAME 이면 호출 불가. */
    public void updateTeamPolicy(String roomId, TeamPolicy newPolicy) {
        if (Boolean.FALSE.equals(redis.hasKey("room:" + roomId))) {
            throw new RoomNotFoundException(roomId);
        }
        redis.opsForHash().put("room:" + roomId, "teamPolicy", newPolicy.name());
    }

    public int join(String roomId, long userId, long now) {
        Long result = redis.execute(
                joinScript,
                // D-137 — KEYS[4] 관전자 SET: 앉으면 같은 원자 구간에서 관전자 목록에서 뺀다.
                List.of("room:" + roomId, "room:" + roomId + ":players", ROOMS_OPEN_KEY, spectatorsKey(roomId)),
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

    /**
     * Phase 16(#2) — ready 토글. 반환 1 이면 전원 ready 로 IN_GAME 전이됨
     * (호출측이 GameStartingEvent 발행), 0 이면 토글만.
     */
    public int setReady(String roomId, long userId, boolean ready) {
        Long result = redis.execute(
                readyScript,
                List.of("room:" + roomId,
                        "room:" + roomId + ":players",
                        readyKey(roomId),
                        ROOMS_OPEN_KEY),
                Long.toString(userId),
                ready ? "1" : "0",
                roomId);
        long v = unwrap(result);
        if (v == -1L) throw new RoomNotFoundException(roomId);
        if (v == -2L) throw new GameAlreadyStartedException(roomId);
        if (v == -3L) throw new NotInRoomException(roomId);
        return (int) v;
    }

    /**
     * D-136·D-143 — 방장 시작(room_start_now.lua). 반환: 1 시작(정원 := 현재 인원), -1 방 없음, -2 대기 중 아님, -3 멤버 아님,
     * -4 최소 인원 미만. 해석은 호출측({@link RoomService#startNow}).
     */
    public int startNow(String roomId, long hostUserId, int minPlayers) {
        Long result = redis.execute(
                startNowScript,
                List.of("room:" + roomId,
                        "room:" + roomId + ":players",
                        readyKey(roomId),
                        ROOMS_OPEN_KEY),
                Long.toString(hostUserId),
                Integer.toString(minPlayers),
                roomId);
        if (result == null) {
            throw new IllegalStateException("room_start_now.lua returned null");
        }
        return result.intValue();
    }

    /**
     * D-153 — 정상 종료한 친구 방을 대기실로(room_return_waiting.lua). 반환: 돌아간 뒤 좌석 수, -1 방 없음, -2 진행 중 아님(그대로).
     */
    public int returnToWaiting(String roomId, int maxPlayers, long now) {
        Long result = redis.execute(
                returnWaitingScript,
                List.of("room:" + roomId,
                        "room:" + roomId + ":players",
                        readyKey(roomId),
                        spectatorsKey(roomId),
                        spectatorsSinceKey(roomId),
                        ROOMS_OPEN_KEY),
                Integer.toString(maxPlayers),
                roomId,
                Long.toString(now));
        if (result == null) {
            throw new IllegalStateException("room_return_waiting.lua returned null");
        }
        return result.intValue();
    }

    private static String readyKey(String roomId) {
        return "room:" + roomId + ":ready";
    }

    public void markFinished(String roomId, long now) {
        // D-122 — 좌석·준비·관전 키도 메타와 같은 600s 로 줄인다. FINISHED 방은 마지막 leave 로
        // 지워지지 않으므로(좌석 고정) 그대로 두면 6h 동안 남는다.
        Long result = redis.execute(
                finishScript,
                List.of("room:" + roomId, ROOMS_OPEN_KEY,
                        "room:" + roomId + ":players", readyKey(roomId), spectatorsKey(roomId)),
                roomId,
                Long.toString(now));
        long v = unwrap(result);
        if (v == -1L) throw new RoomNotFoundException(roomId);
    }

    /**
     * 플레이어 퇴장 (room_leave.lua). WAITING/IN_GAME 은 좌석 목록에서 빼고(빈 방이면 소멸,
     * 호스트면 승격), <b>FINISHED 는 좌석을 그대로 둔다</b>(D-122) — 시작된 게임의 좌석
     * 인덱스는 불변이라 남은 사람의 좌석·비공개 뷰가 밀리지 않는다. 방은 TTL 로 사라진다.
     */
    public void leave(String roomId, long userId) {
        // D-74: ready SET, D-75: spectators SET 도 빈 방 destroy 시 정리하므로
        // KEYS[4]/KEYS[5] 로 추가 전달 (join/create 와 공유하는 keysFor 는 불변).
        List<String> keys = List.of(
                "room:" + roomId,
                "room:" + roomId + ":players",
                ROOMS_OPEN_KEY,
                readyKey(roomId),
                spectatorsKey(roomId));
        Long result = redis.execute(
                leaveScript,
                keys,
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
        Set<String> readyStrings = redis.opsForSet().members(readyKey(roomId));
        Set<Long> readyUserIds = readyStrings == null
                ? Set.of()
                : readyStrings.stream().map(Long::parseLong).collect(java.util.stream.Collectors.toUnmodifiableSet());
        // Phase 8C — teamPolicy 컬럼이 없는 (V1 이전) 방은 SEQUENTIAL 기본값.
        String rawPolicy = (String) hash.get("teamPolicy");
        TeamPolicy teamPolicy = rawPolicy == null ? TeamPolicy.SEQUENTIAL : TeamPolicy.valueOf(rawPolicy);
        // Phase 9B — fillWithBots 가 없으면 (구방) false 기본값.
        boolean fillWithBots = "true".equals((String) hash.get("fillWithBots"));
        // Phase 12 — targetScore 없으면 (구방) 1000 기본값.
        String rawTarget = (String) hash.get("targetScore");
        int targetScore = rawTarget == null ? 1000 : Integer.parseInt(rawTarget);
        // Phase 13D — turnSeconds 없으면 (구방) 0 (끔) 기본값.
        String rawTurn = (String) hash.get("turnSeconds");
        int turnSeconds = rawTurn == null ? 0 : Integer.parseInt(rawTurn);
        // D-81 — stake 없으면 (구방) 0 (내기 없음) 기본값.
        String rawStake = (String) hash.get("stake");
        int stake = rawStake == null ? 0 : Integer.parseInt(rawStake);
        // botSeats: playerIds 인덱스 중 봇 user id 인 좌석.
        List<Integer> botSeats = new ArrayList<>();
        for (int i = 0; i < playerIds.size(); i++) {
            if (bots.isBot(playerIds.get(i))) {
                botSeats.add(i);
            }
        }
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
                teamPolicy,
                Long.parseLong((String) hash.get("createdAt")),
                fillWithBots,
                List.copyOf(botSeats),
                targetScore,
                turnSeconds,
                stake,
                readyUserIds));
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
        // D-153 — 들어온 순서(다음 판 자동 착석 순서). 다시 관전하면 시각이 새로 찍힌다. 나간 관전자는 SET 과 맞춰 걸러진다.
        redis.opsForZSet().add(spectatorsSinceKey(roomId), Long.toString(userId), System.currentTimeMillis());
        redis.expire(spectatorsSinceKey(roomId), java.time.Duration.ofHours(6));
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

    private static String spectatorsSinceKey(String roomId) {
        return "room:" + roomId + ":spectators_since";
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
