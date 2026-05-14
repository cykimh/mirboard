package com.mirboard.infra.ws.lobby;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.messaging.StompPublisher;
import com.mirboard.infra.ws.StompEnvelope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Clock;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * 로비 채팅 STOMP 핸들러. Phase 6D-2 에서 {@code @SendTo} 자동 broker 전송 대신
 * {@link StompPublisher} 로 명시 publish — 다른 인스턴스에 붙은 클라이언트도 채팅을
 * 받도록 fan-out.
 */
@Controller
public class LobbyStompController {

    public static final String LOBBY_CHAT_TOPIC = "/topic/lobby/chat";

    private final Clock clock;
    private final StompPublisher publisher;

    public LobbyStompController(Clock clock, StompPublisher publisher) {
        this.clock = clock;
        this.publisher = publisher;
    }

    @MessageMapping("/lobby/chat")
    public void handleLobbyChat(@Payload ChatRequest req, Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        var envelope = StompEnvelope.of(
                "CHAT",
                new ChatMessage(me.userId(), me.username(), req.message()),
                clock);
        publisher.publishToTopic(LOBBY_CHAT_TOPIC, envelope);
    }

    public record ChatRequest(@NotBlank @Size(max = 500) String message) {
    }

    public record ChatMessage(long userId, String username, String message) {
    }
}
