package com.mirboard.infra.ws.lobby;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.ws.StompEnvelope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Clock;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class LobbyStompController {

    private final Clock clock;

    public LobbyStompController(Clock clock) {
        this.clock = clock;
    }

    @MessageMapping("/lobby/chat")
    @SendTo("/topic/lobby/chat")
    public StompEnvelope<ChatMessage> handleLobbyChat(@Payload ChatRequest req, Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        return StompEnvelope.of(
                "CHAT",
                new ChatMessage(me.userId(), me.username(), req.message()),
                clock);
    }

    public record ChatRequest(@NotBlank @Size(max = 500) String message) {
    }

    public record ChatMessage(long userId, String username, String message) {
    }
}
