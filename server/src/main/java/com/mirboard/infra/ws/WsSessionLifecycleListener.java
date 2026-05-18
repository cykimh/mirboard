package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Phase 19(#1, D-75) — STOMP SUBSCRIBE/DISCONNECT 후킹.
 *
 * <p>방 화면(대기실 `/topic/room/{id}/meta`, 게임 `/topic/room/{id}` 및
 * `/chat`)을 구독할 때 세션→방을 {@link WsSessionRegistry} 에 기록하고,
 * 끊김 시 제거 후 {@link RoomDisconnectHandler} 로 정리/유예를 위임한다.
 * 로비 채팅(`/topic/lobby/chat`) 등 방과 무관한 구독은 무시한다.
 */
@Component
public class WsSessionLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(WsSessionLifecycleListener.class);

    /** `/topic/room/{roomId}` (bare / /meta / /chat) — roomId 1캡처. */
    private static final Pattern ROOM_TOPIC =
            Pattern.compile("^/topic/room/([^/]+)(?:/.*)?$");

    private final WsSessionRegistry registry;
    private final RoomDisconnectHandler disconnectHandler;

    public WsSessionLifecycleListener(WsSessionRegistry registry,
                                      RoomDisconnectHandler disconnectHandler) {
        this.registry = registry;
        this.disconnectHandler = disconnectHandler;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        if (destination == null || sessionId == null) {
            return;
        }
        Matcher m = ROOM_TOPIC.matcher(destination);
        if (!m.matches()) {
            return;
        }
        Long userId = userIdOf(event.getUser());
        if (userId == null) {
            return;
        }
        registry.register(sessionId, userId, m.group(1));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) {
            return;
        }
        registry.remove(sessionId).ifPresent(info -> {
            try {
                disconnectHandler.onDisconnect(info.roomId(), info.userId());
            } catch (RuntimeException e) {
                log.warn("WS disconnect cleanup failed: roomId={} userId={} err={}",
                        info.roomId(), info.userId(), e.toString());
            }
        });
    }

    private static Long userIdOf(Principal principal) {
        return (principal instanceof AuthPrincipal ap) ? ap.userId() : null;
    }
}
