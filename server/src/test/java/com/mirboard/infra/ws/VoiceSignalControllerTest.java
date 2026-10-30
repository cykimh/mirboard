package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.messaging.StompPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * D-155 — 음성 대화 신호 중계. 서버는 소리를 보지 않고 P2P 연결을 맺는 짧은 메시지만 넘긴다: JOIN·LEAVE 는 방 안 공지,
 * OFFER·ANSWER·ICE 는 받는 사람 본인 큐로만. 보낸 사람·받는 사람 모두 그 방 플레이어나 관전자여야 하고, 내용은 해석하지 않되 길이를 막는다.
 */
class VoiceSignalControllerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final StompPublisher publisher = mock(StompPublisher.class);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final VoiceSignalController controller = new VoiceSignalController(clock, publisher, roomService);

    private static AuthPrincipal user(long id) {
        return new AuthPrincipal(id, "u" + id);
    }

    @Test
    void join_is_announced_to_the_room() {
        when(roomService.isParticipantOrSpectator("r1", 3L)).thenReturn(true);

        controller.handle("r1", new VoiceSignalController.VoiceSignal("JOIN", null, null), user(3));

        ArgumentCaptor<StompEnvelope> cap = ArgumentCaptor.forClass(StompEnvelope.class);
        verify(publisher).publishToTopic(eq("/topic/room/r1/voice"), cap.capture());
        assertThat(cap.getValue().type()).isEqualTo("VOICE");
        var msg = (VoiceSignalController.VoiceMessage) cap.getValue().payload();
        assertThat(msg.type()).isEqualTo("JOIN");
        assertThat(msg.from()).isEqualTo(3L);
        assertThat(msg.data()).isNull();
    }

    @Test
    void offer_goes_only_to_the_target_user() {
        when(roomService.isParticipantOrSpectator("r1", 3L)).thenReturn(true);
        when(roomService.isParticipantOrSpectator("r1", 5L)).thenReturn(true);

        controller.handle("r1", new VoiceSignalController.VoiceSignal("OFFER", 5L, "{\"sdp\":\"v=0\"}"), user(3));

        ArgumentCaptor<StompEnvelope> cap = ArgumentCaptor.forClass(StompEnvelope.class);
        verify(publisher).publishToUser(eq(5L), eq("/queue/room/r1/voice"), cap.capture());
        verify(publisher, never()).publishToTopic(any(), any());
        var msg = (VoiceSignalController.VoiceMessage) cap.getValue().payload();
        assertThat(msg.type()).isEqualTo("OFFER");
        assertThat(msg.from()).isEqualTo(3L);
        assertThat(msg.data()).isEqualTo("{\"sdp\":\"v=0\"}");
    }

    @Test
    void sender_outside_the_room_is_ignored() {
        when(roomService.isParticipantOrSpectator("r1", 9L)).thenReturn(false);

        controller.handle("r1", new VoiceSignalController.VoiceSignal("JOIN", null, null), user(9));

        verify(publisher, never()).publishToTopic(any(), any());
    }

    @Test
    void target_outside_the_room_is_ignored() {
        when(roomService.isParticipantOrSpectator("r1", 3L)).thenReturn(true);
        when(roomService.isParticipantOrSpectator("r1", 7L)).thenReturn(false);

        controller.handle("r1", new VoiceSignalController.VoiceSignal("ICE", 7L, "{}"), user(3));

        verify(publisher, never()).publishToUser(anyLong(), any(), any());
    }

    @Test
    void unknown_type_missing_target_self_target_and_oversized_data_are_ignored() {
        when(roomService.isParticipantOrSpectator(eq("r1"), anyLong())).thenReturn(true);

        controller.handle("r1", new VoiceSignalController.VoiceSignal("HACK", 5L, "{}"), user(3));
        controller.handle("r1", new VoiceSignalController.VoiceSignal("OFFER", null, "{}"), user(3));
        controller.handle("r1", new VoiceSignalController.VoiceSignal("OFFER", 3L, "{}"), user(3));
        controller.handle("r1", new VoiceSignalController.VoiceSignal("OFFER", 5L, "x".repeat(16 * 1024 + 1)), user(3));
        controller.handle("r1", null, user(3));

        verify(publisher, never()).publishToUser(anyLong(), any(), any());
        verify(publisher, never()).publishToTopic(any(), any());
    }

    @Test
    void a_vanished_room_is_ignored() {
        when(roomService.isParticipantOrSpectator("gone", 3L)).thenThrow(new RoomNotFoundException("gone"));

        controller.handle("gone", new VoiceSignalController.VoiceSignal("LEAVE", null, null), user(3));

        verify(publisher, never()).publishToTopic(any(), any());
    }
}
