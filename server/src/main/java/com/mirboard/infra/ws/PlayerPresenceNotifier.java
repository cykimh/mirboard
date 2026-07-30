package com.mirboard.infra.ws;

import com.mirboard.infra.messaging.StompPublisher;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * IN_GAME 플레이어의 끊김/재접속을 같은 방의 게임 토픽(`/topic/room/{id}`)으로 알린다.
 * 다른 좌석 클라이언트가 해당 좌석에 "연결 끊김" 배지를 즉시 표시할 수 있게 한다.
 *
 * <p>게임 룰 이벤트({@code GameEvent})가 아니라 세션 메타이므로 seq 없는 envelope 으로
 * 발행한다(RoomLobbyEventPublisher 와 동일 패턴). 봇은 WS 세션이 없어 호출되지 않는다.
 */
@Component
public class PlayerPresenceNotifier {

    private final StompPublisher publisher;
    private final Clock clock;

    public PlayerPresenceNotifier(StompPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void disconnected(String roomId, int seat) {
        publish(roomId, "PLAYER_DISCONNECTED", seat);
    }

    public void reconnected(String roomId, int seat) {
        publish(roomId, "PLAYER_RECONNECTED", seat);
    }

    private void publish(String roomId, String type, int seat) {
        publisher.publishToTopic("/topic/room/" + roomId,
                StompEnvelope.of(type, new SeatPayload(seat), clock));
    }

    public record SeatPayload(int seat) {
    }
}
