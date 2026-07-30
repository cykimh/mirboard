package com.mirboard.infra.ws.lobby;

import com.mirboard.domain.admin.ChatLogStore;
import com.mirboard.domain.admin.ChatModerationService;
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
    private final ChatModerationService chatModeration;
    private final ChatLogStore chatLog;

    public LobbyStompController(Clock clock, StompPublisher publisher,
                               ChatModerationService chatModeration,
                               ChatLogStore chatLog) {
        this.clock = clock;
        this.publisher = publisher;
        this.chatModeration = chatModeration;
        this.chatLog = chatLog;
    }

    @MessageMapping("/lobby/chat")
    public void handleLobbyChat(@Payload ChatRequest req, Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        String masked = chatModeration.mask(req.message());
        var envelope = StompEnvelope.of(
                "CHAT",
                new ChatMessage(me.userId(), me.username(), masked),
                clock);
        publisher.publishToTopic(LOBBY_CHAT_TOPIC, envelope);
        // D-93 — 신고 시 서버가 원문을 확정할 근거(TTL 2h, 최근 100개). 영속 로그 아님.
        chatLog.record(ChatLogStore.SCOPE_LOBBY, null, new ChatLogStore.Entry(
                envelope.eventId(), me.userId(), me.username(), masked, envelope.ts()));
    }

    public record ChatRequest(@NotBlank @Size(max = 500) String message) {
    }

    public record ChatMessage(long userId, String username, String message) {
    }
}
