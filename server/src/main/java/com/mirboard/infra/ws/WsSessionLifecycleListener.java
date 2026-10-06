package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.bot.GameProgressKick;
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
 * `/chat`)을 구독할 때 세션→방을 {@link RoomPresence}(Redis) 에 기록하고,
 * 끊김 시 제거 후 {@link RoomDisconnectHandler} 로 정리/유예를 위임한다.
 * 로비 채팅(`/topic/lobby/chat`) 등 방과 무관한 구독은 무시한다.
 *
 * <p>D-130 — 게임 토픽 그 자체(`/topic/room/{id}`) 구독은 클라가 (재)접속해 판을 다시 보기 시작한 순간이다(배포
 * 뒤에는 모든 클라가 다시 붙는다). 그때 {@link GameProgressKick} 을 건다(킥은 그 방의 참가자·관전자 구독만 받는다).
 * 같은 세션이 함께 구독하는 메타·채팅·리액션 토픽에는 걸지 않는다 — 한 접속에 한 번이면 된다.
 */
@Component
public class WsSessionLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(WsSessionLifecycleListener.class);

    /** `/topic/room/{roomId}` (bare / /meta / /chat) — roomId 1캡처. */
    private static final Pattern ROOM_TOPIC =
            Pattern.compile("^/topic/room/([^/]+)(?:/.*)?$");

    private final RoomPresence presence;
    private final RoomDisconnectHandler disconnectHandler;
    private final GameProgressKick kick;

    public WsSessionLifecycleListener(RoomPresence presence,
                                      RoomDisconnectHandler disconnectHandler,
                                      GameProgressKick kick) {
        this.presence = presence;
        this.disconnectHandler = disconnectHandler;
        this.kick = kick;
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
        String roomId = m.group(1);
        presence.join(sessionId, userId, roomId);
        // 끊김 유예 중이던 플레이어가 방 토픽을 재구독 = 재접속 → 유예 취소 + 알림.
        disconnectHandler.onReconnect(roomId, userId);
        // D-130 — 게임 토픽 그 자체일 때만(비동기 — 구독 처리를 늦추지 않는다).
        if (destination.equals("/topic/room/" + roomId)) {
            kick.kick(roomId, userId);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) {
            return;
        }
        presence.leave(sessionId).ifPresent(info -> {
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
