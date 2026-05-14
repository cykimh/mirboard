package com.mirboard.infra.ws;

import com.mirboard.domain.game.tichu.event.TichuEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 엔진이 반환한 이벤트들을 STOMP 토픽/큐로 분기 발행. 공개 이벤트는 `/topic/room/{id}` 로,
 * 비공개(HandDealt) 는 `/user/{userId}/queue/room/{id}` 로 보낸다. envelope 의 단조
 * 증가 seq 는 `room:{id}:seq` INCR 로 부여.
 */
@Component
public class GameEventBroadcaster {

    private final SimpMessagingTemplate broker;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public GameEventBroadcaster(SimpMessagingTemplate broker, StringRedisTemplate redis, Clock clock) {
        this.broker = broker;
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
                broker.convertAndSendToUser(Long.toString(userId),
                        "/queue/room/" + roomId, envelope);
            } else {
                broker.convertAndSend("/topic/room/" + roomId, envelope);
            }
        }
    }

    /** 본인에게만 ERROR 응답 — 검증 실패 시 컨트롤러가 호출. */
    public void sendErrorTo(long userId, String roomId, String code, String message) {
        long ts = Instant.now(clock).toEpochMilli();
        var envelope = new StompEnvelope<>(UUID.randomUUID().toString(), "ERROR", ts, null,
                Map.of("code", code, "message", message));
        broker.convertAndSendToUser(Long.toString(userId),
                "/queue/room/" + roomId, envelope);
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
