package com.mirboard.infra.ws;

import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.infra.messaging.StompPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 엔진이 반환한 이벤트들을 STOMP 토픽/큐로 분기 발행. 공개 이벤트는 `/topic/room/{id}` 로,
 * 비공개 이벤트는 `/user/{userId}/queue/room/{id}` 로 보낸다. envelope 의 단조 증가 seq 는
 * {@link RoomSeq} 가 부여.
 *
 * <p>Phase 6D-2: 직접 {@code SimpMessagingTemplate} 호출 대신 {@link StompPublisher}
 * 를 거쳐 모든 인스턴스로 fan-out. 단일 인스턴스 환경에선 InMemoryMessageGateway 가
 * 동기로 같은 JVM 내 relay 콜백을 호출해 동일 동작.
 *
 * <p>D-98: 게임을 모른다. 라우팅에 필요한 두 질문("envelope type 은?", "누구에게?")을
 * {@link GameEvent} 가 답하고, payload 는 Jackson 이 런타임 타입 그대로 직렬화한다.
 */
@Component
public class GameEventBroadcaster {

    private final StompPublisher publisher;
    private final RoomSeq seqs;
    private final Clock clock;

    public GameEventBroadcaster(StompPublisher publisher, RoomSeq seqs, Clock clock) {
        this.publisher = publisher;
        this.seqs = seqs;
        this.clock = clock;
    }

    public void broadcast(String roomId, List<? extends GameEvent> events, List<Long> playerIds) {
        for (GameEvent ev : events) {
            long seq = seqs.next(roomId);
            String eventId = UUID.randomUUID().toString();
            long ts = Instant.now(clock).toEpochMilli();
            var envelope = new StompEnvelope<>(eventId, ev.envelopeType(), ts, seq, ev);

            int seat = ev.privateSeat();
            if (seat >= 0) {
                // State Hiding (D-01) — 비공개 이벤트는 본인 큐로만. 좌석이 방 범위를
                // 벗어나면 보낼 곳이 없으므로 토픽으로 폴백하지 않고 버린다.
                if (seat >= playerIds.size()) continue;
                publisher.publishToUser(playerIds.get(seat), "/queue/room/" + roomId, envelope);
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
}
