package com.mirboard.infra.ws;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * D-96 — 방 프레즌스(누가 이 방을 보고 있는가)를 Redis 로 공유한다.
 *
 * <p>D-75 의 {@code WsSessionRegistry} 는 in-memory 라 인스턴스 A 에 붙은 세션을 B 가
 * 알 수 없었다. 그 상태로 2인스턴스가 되면 <b>재접속했는데 탈주로 처리</b>된다 —
 * 탈주 유예를 건 인스턴스가 재접속을 못 보기 때문. 그래서 공유 저장소로 옮긴다.
 *
 * <p><b>boolean 이 아니라 세션 카운터다.</b> 한 유저가 탭을 두 개 열었다가 하나만 닫아도
 * 여전히 접속 중이어야 하므로 HASH 필드에 세션 수를 센다.
 *
 * <p>키는 두 종류다.
 * <ul>
 *   <li>{@code presence:room:{roomId}} — HASH userId→세션 수. "이 방에 누가 있나".</li>
 *   <li>{@code presence:session:{sessionId}} — STRING "{userId}:{roomId}".
 *       DISCONNECT 는 sessionId 만 주므로 역방향 조회가 필요하다. 동시에 "이 세션을
 *       이미 셌는가"의 표식이라 {@link #join} 이 구독마다 불려도 카운터는 한 번만 는다.</li>
 * </ul>
 */
@Component
public class RoomPresence {

    /** 고아 키 방지. 방 TTL(6h)보다 짧게 잡되 최장 매치보다는 길게. */
    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> joinScript;
    private final RedisScript<Long> leaveScript;

    public RoomPresence(StringRedisTemplate redis,
                        @Qualifier("presenceJoinScript") RedisScript<Long> joinScript,
                        @Qualifier("presenceLeaveScript") RedisScript<Long> leaveScript) {
        this.redis = redis;
        this.joinScript = joinScript;
        this.leaveScript = leaveScript;
    }

    /**
     * 방 토픽 SUBSCRIBE 시 호출. 같은 유저의 두 번째 탭이면 카운터가 증가하지만,
     * <b>같은 세션의 추가 구독은 세지 않는다</b> — 호출자는 구독마다 부르는데 DISCONNECT
     * 는 세션당 한 번뿐이라, 멱등하지 않으면 잔여 카운터가 남아 끊긴 사용자가 영영
     * 접속 중으로 보인다(= 탈주 미확정). 멱등 판정은 `presence_join.lua` 안에서 원자적.
     */
    public void join(String sessionId, long userId, String roomId) {
        String existing = redis.opsForValue().get(sessionKey(sessionId));
        if (existing != null && !existing.equals(userId + ":" + roomId)) {
            // 세션이 다른 방(또는 다른 유저)을 보고 있었다 — 옛 방 카운터부터 내려야
            // 그 방에 고아 프레즌스가 남지 않는다. 클라는 방마다 새 소켓을 열지만
            // 구독은 클라가 보내는 값이므로 신뢰하지 않는다(Server-Authoritative).
            leave(sessionId);
        }
        redis.execute(joinScript, List.of(roomKey(roomId), sessionKey(sessionId)),
                String.valueOf(userId), roomId, String.valueOf(TTL.toSeconds()));
    }

    /**
     * DISCONNECT 시 호출. 세션→(user,room) 을 역조회해 카운터를 내린다.
     *
     * @return 해당 세션이 보고 있던 (userId, roomId). 등록된 적 없으면 empty.
     */
    public Optional<SessionInfo> leave(String sessionId) {
        String raw = redis.opsForValue().get(sessionKey(sessionId));
        if (raw == null) {
            return Optional.empty();
        }
        redis.delete(sessionKey(sessionId));
        int sep = raw.indexOf(':');
        if (sep < 0) {
            return Optional.empty();
        }
        long userId;
        try {
            userId = Long.parseLong(raw.substring(0, sep));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String roomId = raw.substring(sep + 1);
        redis.execute(leaveScript, List.of(roomKey(roomId)),
                String.valueOf(userId), String.valueOf(TTL.toSeconds()));
        return Optional.of(new SessionInfo(userId, roomId));
    }

    /**
     * 해당 유저가 그 방에 살아 있는 세션을 (아직) 갖고 있는지.
     * 탈주 유예 만료 시 "재접속했는가" 판정에 쓴다 — 이 판정이 인스턴스를 넘어
     * 정확해지는 것이 D-96 의 핵심이다.
     */
    public boolean hasLiveSession(long userId, String roomId) {
        Object count = redis.opsForHash().get(roomKey(roomId), String.valueOf(userId));
        if (count == null) {
            return false;
        }
        try {
            return Long.parseLong(count.toString()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 현재 이 방을 보고 있는 userId 집합 (관전자 포함). */
    public Set<Long> viewers(String roomId) {
        Map<Object, Object> all = redis.opsForHash().entries(roomKey(roomId));
        return all.keySet().stream()
                .map(Object::toString)
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** 방 소멸 시 정리. */
    public void clearRoom(String roomId) {
        redis.delete(roomKey(roomId));
    }

    static String roomKey(String roomId) {
        return "presence:room:" + roomId;
    }

    static String sessionKey(String sessionId) {
        return "presence:session:" + sessionId;
    }

    public record SessionInfo(long userId, String roomId) {
    }
}
