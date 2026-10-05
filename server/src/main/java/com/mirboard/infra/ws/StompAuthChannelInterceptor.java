package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.InvalidCredentialsException;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.domain.lobby.auth.SuspensionService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String RAW_QUEUE_PREFIX = "/queue/";

    private final JwtService jwtService;
    private final SuspensionService suspensions;

    public StompAuthChannelInterceptor(JwtService jwtService, SuspensionService suspensions) {
        this.jwtService = jwtService;
        this.suspensions = suspensions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader(AUTH_HEADER);
            if (header == null || !header.startsWith(BEARER)) {
                throw new MessageDeliveryException(
                        "STOMP CONNECT requires Authorization: Bearer <token>");
            }
            try {
                AuthPrincipal principal = jwtService.parse(header.substring(BEARER.length()).trim());
                // D-86 — 정지된 계정은 새 소켓 연결 차단(기존 소켓은 토큰 만료까지 유지).
                if (suspensions.isSuspended(principal.userId())) {
                    throw new MessageDeliveryException("Account suspended");
                }
                accessor.setUser(principal);
            } catch (InvalidCredentialsException e) {
                throw new MessageDeliveryException("Invalid or expired JWT");
            }
        }

        // D-126 — 비공개 큐는 늘 `/user/queue/...` 로 구독한다(Spring 이 본인 세션 목적지로 바꾼다).
        // 브로커가 `/queue` 를 처리하므로, 변환 전 `/queue/...` 를 직접 구독하게 두면 남의 세션 id 를
        // 아는 클라가 `/queue/room/{id}-user{세션}` 으로 남의 손패를 받을 수 있다(State Hiding).
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            if (destination != null && destination.startsWith(RAW_QUEUE_PREFIX)) {
                throw new MessageDeliveryException(
                        "Subscribe to /user/queue/..., not " + RAW_QUEUE_PREFIX + "...");
            }
        }

        return message;
    }
}
