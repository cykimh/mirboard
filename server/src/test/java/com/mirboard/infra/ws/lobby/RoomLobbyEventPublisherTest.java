package com.mirboard.infra.ws.lobby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomChangedEvent;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.messaging.StompPublisher;
import com.mirboard.infra.ws.StompEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RoomLobbyEventPublisherTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-17T00:00:00Z"), ZoneOffset.UTC);

    private static Room room(String id) {
        return new Room(id, "방", "TICHU", 1L, RoomStatus.WAITING, 4, 1,
                List.of(1L), Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000);
    }

    @Test
    void updated_publishes_to_lobby_and_per_room_meta() {
        StompPublisher publisher = Mockito.mock(StompPublisher.class);
        var sut = new RoomLobbyEventPublisher(publisher, CLOCK);

        sut.onRoomChanged(RoomChangedEvent.updated(room("r1")));

        verify(publisher).publishToTopic(eq(RoomLobbyEventPublisher.LOBBY_ROOMS_TOPIC), any());
        verify(publisher).publishToTopic(eq("/topic/room/r1/meta"), any());
    }

    @Test
    void per_room_meta_envelope_type_is_ROOM_META_UPDATED() {
        StompPublisher publisher = Mockito.mock(StompPublisher.class);
        var sut = new RoomLobbyEventPublisher(publisher, CLOCK);

        sut.onRoomChanged(RoomChangedEvent.updated(room("r2")));

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishToTopic(eq("/topic/room/r2/meta"), cap.capture());
        assertThat(((StompEnvelope<?>) cap.getValue()).type()).isEqualTo("ROOM_META_UPDATED");
    }

    @Test
    void destroyed_publishes_to_lobby_and_per_room_meta() {
        StompPublisher publisher = Mockito.mock(StompPublisher.class);
        var sut = new RoomLobbyEventPublisher(publisher, CLOCK);

        sut.onRoomChanged(RoomChangedEvent.destroyed("r3"));

        verify(publisher).publishToTopic(eq(RoomLobbyEventPublisher.LOBBY_ROOMS_TOPIC), any());
        verify(publisher).publishToTopic(eq("/topic/room/r3/meta"), any());
    }

    @Test
    void roomMetaTopic_format() {
        assertThat(RoomLobbyEventPublisher.roomMetaTopic("abc")).isEqualTo("/topic/room/abc/meta");
    }
}
