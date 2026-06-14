package com.mirboard.infra.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.messaging.StompPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoomReactionControllerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final StompPublisher publisher = mock(StompPublisher.class);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final RoomReactionController controller =
            new RoomReactionController(clock, publisher, roomService);

    private static Room room() {
        return new Room("r1", "방", "TICHU", 1L, RoomStatus.IN_GAME, 4, 4,
                List.of(1L, 2L, 3L, 4L), Set.of(), TeamPolicy.SEQUENTIAL,
                0L, false, List.of(), 1000, 0, Set.of());
    }

    @Test
    void allowed_emoji_from_participant_broadcasts() {
        when(roomService.isParticipantOrSpectator("r1", 3L)).thenReturn(true);
        when(roomService.getRoom("r1")).thenReturn(room());

        controller.handleReaction("r1",
                new RoomReactionController.ReactionRequest("🔥"),
                new AuthPrincipal(3L, "carol"));

        verify(publisher).publishToTopic(eq("/topic/room/r1/reaction"), any());
    }

    @Test
    void disallowed_emoji_ignored_before_room_lookup() {
        controller.handleReaction("r1",
                new RoomReactionController.ReactionRequest("💣"),
                new AuthPrincipal(3L, "carol"));

        verify(publisher, never()).publishToTopic(any(), any());
        verify(roomService, never()).getRoom(any());
    }

    @Test
    void non_participant_ignored() {
        when(roomService.isParticipantOrSpectator("r1", 9L)).thenReturn(false);

        controller.handleReaction("r1",
                new RoomReactionController.ReactionRequest("👍"),
                new AuthPrincipal(9L, "mallory"));

        verify(publisher, never()).publishToTopic(any(), any());
    }
}
