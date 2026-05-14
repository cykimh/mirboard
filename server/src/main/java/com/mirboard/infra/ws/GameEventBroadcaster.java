package com.mirboard.infra.ws;

import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.infra.messaging.StompPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 엔진이 반환한 이벤트들을 STOMP 토픽/큐로 분기 발행. 공개 이벤트는 `/topic/room/{id}` 로,
 * 비공개(HandDealt) 는 `/user/{userId}/queue/room/{id}` 로 보낸다. envelope 의 단조
 * 증가 seq 는 `room:{id}:seq` INCR 로 부여.
 *
 * <p>Phase 6D-2: 직접 {@code SimpMessagingTemplate} 호출 대신 {@link StompPublisher}
 * 를 거쳐 모든 인스턴스로 fan-out. 단일 인스턴스 환경에선 InMemoryMessageGateway 가
 * 동기로 같은 JVM 내 relay 콜백을 호출해 동일 동작.
 */
@Component
public class GameEventBroadcaster {

    private final StompPublisher publisher;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public GameEventBroadcaster(StompPublisher publisher, StringRedisTemplate redis, Clock clock) {
        this.publisher = publisher;
        this.redis = redis;
        this.clock = clock;
    }

    public void broadcast(String roomId, List<TichuEvent> events, List<Long> playerIds) {
        for (TichuEvent ev : events) {
            long seq = nextSeq(roomId);
            String eventId = UUID.randomUUID().toString();
            long ts = Instant.now(clock).toEpochMilli();
            var envelope = new StompEnvelope<>(eventId, ev.envelopeType(), ts, seq, ev);

            if (ev.isPrivate()) {
                int seat = seatOf(ev);
                if (seat < 0 || seat >= playerIds.size()) continue;
                long userId = playerIds.get(seat);
                publisher.publishToUser(userId, "/queue/room/" + roomId, envelope);
            } else {
                publisher.publishToTopic("/topic/room/" + roomId, envelope);
            }
        }
    }

    /** 본인에게만 ERROR 응답 — 검증 실패 시 컨트롤러가 호출. */
    public void sendErrorTo(long userId, String roomId, String code, String message) {
        long ts = Instant.now(clock).toEpochMilli();
        var envelope = new StompEnvelope<>(UUID.randomUUID().toString(), "ERROR", ts, null,
                Map.of("code", code, "message", message));
        publisher.publishToUser(userId, "/queue/room/" + roomId, envelope);
    }

    private long nextSeq(String roomId) {
        Long incremented = redis.opsForValue().increment("room:" + roomId + ":seq");
        return incremented == null ? 0L : incremented;
    }

    private static int seatOf(TichuEvent ev) {
        return switch (ev) {
            case TichuEvent.HandDealt hd -> hd.seat();
            default -> -1;
        };
    }
}
