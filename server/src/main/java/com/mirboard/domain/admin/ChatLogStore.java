package com.mirboard.domain.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * D-93 — 최근 채팅 메시지 링버퍼(Redis). 신고 시 서버가 원문·작성자를 **자기 보관분에서
 * 확정**하기 위한 것이다 — 클라가 본문을 제출하면 "상대가 이런 말을 했다"를 위조할 수
 * 있어 무고가 가능하기 때문(Server-Authoritative).
 *
 * <p>상시 채팅 로그 영속화가 아니다. TTL {@value #TTL_HOURS}h · 최근 {@value #MAX_ENTRIES}개만
 * 유지하고, 이 중 <b>신고된 것만</b> `chat_reports` 로 승격된다(D-93 개인정보 범위).
 *
 * <p>Redis 라 멀티 인스턴스에서도 동작한다 — in-memory 였다면 다른 인스턴스가 broadcast 한
 * 메시지를 신고할 수 없다(M3 대비).
 */
@Component
public class ChatLogStore {

    private static final Logger log = LoggerFactory.getLogger(ChatLogStore.class);

    static final int MAX_ENTRIES = 100;
    static final long TTL_HOURS = 2;

    public static final String SCOPE_LOBBY = "LOBBY";
    public static final String SCOPE_ROOM = "ROOM";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ChatLogStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * broadcast 직후 호출. 실패해도 채팅 자체는 계속돼야 하므로 예외를 삼킨다 —
     * 신고 근거가 없어지는 것보다 채팅이 끊기는 게 나쁘다.
     */
    public void record(String scope, String roomId, Entry entry) {
        String key = key(scope, roomId);
        try {
            redis.opsForList().leftPush(key, objectMapper.writeValueAsString(entry));
            redis.opsForList().trim(key, 0, MAX_ENTRIES - 1);
            redis.expire(key, Duration.ofHours(TTL_HOURS));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("채팅 로그 기록 실패 (채팅은 계속): key={} err={}", key, e.toString());
        }
    }

    /** eventId 로 원문 조회. 링버퍼에서 밀려났거나 TTL 만료면 비어 있다. */
    public Optional<Entry> find(String scope, String roomId, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        List<String> raw = redis.opsForList().range(key(scope, roomId), 0, MAX_ENTRIES - 1);
        if (raw == null) {
            return Optional.empty();
        }
        for (String json : raw) {
            try {
                Entry e = objectMapper.readValue(json, Entry.class);
                if (eventId.equals(e.eventId())) {
                    return Optional.of(e);
                }
            } catch (JsonProcessingException ignored) {
                // 포맷이 바뀐 옛 엔트리 — 건너뛴다.
            }
        }
        return Optional.empty();
    }

    static String key(String scope, String roomId) {
        return SCOPE_ROOM.equals(scope) ? "chatlog:room:" + roomId : "chatlog:lobby";
    }

    /**
     * 링버퍼 엔트리. {@code message} 는 broadcast 된 본문 그대로(D-86 마스킹 적용 후) —
     * 어드민이 보는 것과 사용자가 본 것을 일치시킨다.
     */
    public record Entry(String eventId, long userId, String username, String message, long ts) {
    }
}
