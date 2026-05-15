package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.messaging.StompPublisher;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * Phase 8B — 인-게임 채팅 STOMP 핸들러. 로비 채팅 패턴 ({@link
 * com.mirboard.infra.ws.lobby.LobbyStompController}) 답습. 인메모리 (영속화 없음).
 *
 * <p>멤버 검증: 참여자 또는 관전자만 송신 허용. 비-멤버가 보낸 메시지는 무시
 * (broadcast 안 함). 손패 노출과 무관하지만 외부인이 게임 채팅을 도배하는 것은
 * UX 문제이므로 차단.
 */
@Controller
public class RoomChatController {

    private static final Logger log = LoggerFactory.getLogger(RoomChatController.class);

    private final Clock clock;
    private final StompPublisher publisher;
    private final RoomService roomService;

    public RoomChatController(Clock clock, StompPublisher publisher, RoomService roomService) {
        this.clock = clock;
        this.publisher = publisher;
        this.roomService = roomService;
    }

    @MessageMapping("/room/{roomId}/chat")
    public void handleRoomChat(@DestinationVariable String roomId,
                               @Payload ChatRequest req,
                               Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        try {
            if (!roomService.isParticipantOrSpectator(roomId, me.userId())) {
                log.debug("Chat rejected: userId={} not in room={}", me.userId(), roomId);
                return;
            }
        } catch (RoomNotFoundException e) {
            return;
        }
        var envelope = StompEnvelope.of(
                "CHAT",
                new ChatMessage(me.userId(), me.username(), req.message()),
                clock);
        publisher.publishToTopic("/topic/room/" + roomId + "/chat", envelope);
    }

    public record ChatRequest(@NotBlank @Size(max = 500) String message) {
    }

    public record ChatMessage(long userId, String username, String message) {
    }
}
