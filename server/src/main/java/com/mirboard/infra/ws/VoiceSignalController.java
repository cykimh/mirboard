package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.messaging.StompPublisher;
import java.security.Principal;
import java.time.Clock;
import java.util.Set;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * D-155 — 음성 대화(무료 P2P, WebRTC) 신호 중계. 소리는 브라우저끼리 직접 오가고 서버는 연결을 맺는 짧은 메시지만 넘긴다(저장 없음).
 * <ul>
 *   <li>JOIN·LEAVE — 방 안 공지 `/topic/room/{id}/voice`. 서버는 누가 참여 중인지 기억하지 않는다(클라가 공지로 서로 안다).</li>
 *   <li>OFFER·ANSWER·ICE — 받는 사람 본인 큐 `/user/queue/room/{id}/voice` 로만. {@code data}(SDP·ICE 후보 JSON)는 해석하지 않는다.</li>
 * </ul>
 * 보낸 사람·받는 사람 모두 그 방 플레이어나 관전자여야 하고, {@code data} 는 16KB 까지(레이트리밋 버킷 `voice`).
 */
@Controller
public class VoiceSignalController {

    static final String TOPIC = "/topic/room/%s/voice";
    static final String USER_QUEUE = "/queue/room/%s/voice";
    static final int MAX_DATA = 16 * 1024;
    private static final Set<String> BROADCAST = Set.of("JOIN", "LEAVE");
    private static final Set<String> DIRECT = Set.of("OFFER", "ANSWER", "ICE");

    private final Clock clock;
    private final StompPublisher publisher;
    private final RoomService roomService;

    public VoiceSignalController(Clock clock, StompPublisher publisher, RoomService roomService) {
        this.clock = clock;
        this.publisher = publisher;
        this.roomService = roomService;
    }

    @MessageMapping("/room/{roomId}/voice")
    public void handle(@DestinationVariable String roomId, @Payload VoiceSignal signal, Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        if (signal == null || signal.type() == null) {
            return;
        }
        boolean broadcast = BROADCAST.contains(signal.type());
        boolean direct = DIRECT.contains(signal.type());
        if (!broadcast && !direct) {
            return;
        }
        if (signal.data() != null && signal.data().length() > MAX_DATA) {
            return;
        }
        if (direct && (signal.to() == null || signal.to() == me.userId())) {
            return;
        }
        try {
            if (!roomService.isParticipantOrSpectator(roomId, me.userId())) {
                return;
            }
            if (direct && !roomService.isParticipantOrSpectator(roomId, signal.to())) {
                return;
            }
        } catch (RoomNotFoundException e) {
            return;
        }
        var envelope = StompEnvelope.of("VOICE",
                new VoiceMessage(signal.type(), me.userId(), broadcast ? null : signal.data()), clock);
        if (broadcast) {
            publisher.publishToTopic(TOPIC.formatted(roomId), envelope);
        } else {
            publisher.publishToUser(signal.to(), USER_QUEUE.formatted(roomId), envelope);
        }
    }

    /** 클라 → 서버. {@code to} 는 OFFER·ANSWER·ICE 의 받는 사람 userId. */
    public record VoiceSignal(String type, Long to, String data) {
    }

    /** 서버 → 클라. {@code from} 은 보낸 사람 userId(서버가 붙인다 — 클라가 사칭할 수 없다). */
    public record VoiceMessage(String type, long from, String data) {
    }
}
