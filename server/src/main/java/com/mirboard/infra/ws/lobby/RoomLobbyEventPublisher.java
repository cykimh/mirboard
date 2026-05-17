package com.mirboard.infra.ws.lobby;

import com.mirboard.domain.lobby.room.RoomChangedEvent;
import com.mirboard.infra.messaging.StompPublisher;
import com.mirboard.infra.ws.StompEnvelope;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방 도메인 이벤트를 STOMP로 매핑하는 어댑터. 도메인은 STOMP 를 모르므로 의존이
 * 한 방향(infra → domain) 으로 유지된다.
 *
 * <p>Phase 6D-2: SimpMessagingTemplate 직접 호출 대신 {@link StompPublisher} 경유.
 */
@Component
public class RoomLobbyEventPublisher {

    public static final String LOBBY_ROOMS_TOPIC = "/topic/lobby/rooms";

    private final StompPublisher publisher;
    private final Clock clock;

    public RoomLobbyEventPublisher(StompPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    @EventListener
    public void onRoomChanged(RoomChangedEvent event) {
        if (event.isDestroyed()) {
            var destroyed = StompEnvelope.of("ROOM_DESTROYED",
                    new RoomDestroyedPayload(event.roomId()), clock);
            publisher.publishToTopic(LOBBY_ROOMS_TOPIC, destroyed);
            // Phase 13C(#3) — 해당 방 구독자에게도 (RoomPage 폴링 대체).
            publisher.publishToTopic(roomMetaTopic(event.roomId()), destroyed);
        } else {
            publisher.publishToTopic(LOBBY_ROOMS_TOPIC,
                    StompEnvelope.of("ROOM_UPDATED", event.currentState(), clock));
            publisher.publishToTopic(roomMetaTopic(event.roomId()),
                    StompEnvelope.of("ROOM_META_UPDATED", event.currentState(), clock));
        }
    }

    /** Phase 13C — per-room 메타 토픽. RoomPage 가 2초 폴링 대신 구독. */
    public static String roomMetaTopic(String roomId) {
        return "/topic/room/" + roomId + "/meta";
    }

    public record RoomDestroyedPayload(String roomId) {
    }
}
