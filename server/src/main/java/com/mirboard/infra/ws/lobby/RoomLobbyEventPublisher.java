package com.mirboard.infra.ws.lobby;

import com.mirboard.domain.lobby.room.RoomChangedEvent;
import com.mirboard.infra.ws.StompEnvelope;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 방 도메인 이벤트를 STOMP로 매핑하는 어댑터. 도메인은 STOMP 를 모르므로 의존이
 * 한 방향(infra → domain) 으로 유지된다.
 */
@Component
public class RoomLobbyEventPublisher {

    static final String LOBBY_ROOMS_TOPIC = "/topic/lobby/rooms";

    private final SimpMessagingTemplate broker;
    private final Clock clock;

    public RoomLobbyEventPublisher(SimpMessagingTemplate broker, Clock clock) {
        this.broker = broker;
        this.clock = clock;
    }

    @EventListener
    public void onRoomChanged(RoomChangedEvent event) {
        if (event.isDestroyed()) {
            broker.convertAndSend(LOBBY_ROOMS_TOPIC,
                    StompEnvelope.of("ROOM_DESTROYED",
                            new RoomDestroyedPayload(event.roomId()),
                            clock));
        } else {
            broker.convertAndSend(LOBBY_ROOMS_TOPIC,
                    StompEnvelope.of("ROOM_UPDATED", event.currentState(), clock));
        }
    }

    public record RoomDestroyedPayload(String roomId) {
    }
}
