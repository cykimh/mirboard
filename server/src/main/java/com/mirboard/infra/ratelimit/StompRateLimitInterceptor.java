package com.mirboard.infra.ratelimit;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.messaging.StompPublisher;
import com.mirboard.infra.ws.StompEnvelope;
import java.security.Principal;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * D-90 — 클라이언트 인바운드 SEND 레이트리밋. {@link com.mirboard.infra.ws.StompAuthChannelInterceptor}
 * <b>뒤</b>에 등록해 CONNECT 에서 세팅된 {@link AuthPrincipal} 을 키로 쓴다.
 *
 * <p>목적지별로 버킷이 다르다 — 인게임 액션은 정상 연타가 절대 안 걸리도록 관대하게,
 * 채팅은 도배 차단이 목적이라 엄격하게. 표에 없는 `/app/**` 는
 * {@link RateLimitProperties#STOMP_DEFAULT} 로 떨어져(fallback) 새 목적지가 무보호로
 * 태어나지 않는다.
 *
 * <p><b>봇·턴 타임아웃은 영향 없다</b> — {@code BotScheduler}/{@code TurnTimeoutScheduler}
 * 는 STOMP 인바운드를 타지 않고 {@code TichuEngine.apply} 를 직접 호출한다(실측 확인).
 *
 * <p>한도 초과 시 메시지를 드롭한다. 액션 경로는 본인 큐로 `ERROR(RATE_LIMITED)` 를
 * 보내 클라가 이유를 알 수 있게 하고(기존 에러 표시 경로 재사용), 채팅/리액션은 조용히
 * 버린다 — 도배 중인 클라에 에러를 되쏘면 그 자체가 증폭이 되기 때문.
 */
@Component
public class StompRateLimitInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompRateLimitInterceptor.class);

    private static final Pattern ROOM_ACTION = Pattern.compile("^/app/room/([^/]+)/action$");
    private static final Pattern ROOM_CHAT = Pattern.compile("^/app/room/([^/]+)/chat$");
    private static final Pattern ROOM_REACTION = Pattern.compile("^/app/room/([^/]+)/reaction$");
    private static final String LOBBY_CHAT = "/app/lobby/chat";

    private final RateLimiter rateLimiter;
    private final StompPublisher publisher;
    private final Clock clock;

    public StompRateLimitInterceptor(RateLimiter rateLimiter, StompPublisher publisher, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }
        Principal user = accessor.getUser();
        if (!(user instanceof AuthPrincipal me)) {
            // 인증 안 된 SEND — 컨트롤러가 어차피 거부한다. 레이트리밋 대상 아님.
            return message;
        }

        String bucket = bucketFor(destination);
        if (rateLimiter.tryAcquire(bucket, RateLimitSubject.ofUser(me.userId()))) {
            return message;
        }

        log.info("STOMP rate limit exceeded: userId={} destination={} bucket={}",
                me.userId(), destination, bucket);
        notifyIfAction(destination, me.userId());
        return null; // 드롭 — 컨트롤러까지 가지 않는다.
    }

    static String bucketFor(String destination) {
        if (ROOM_ACTION.matcher(destination).matches()) {
            return RateLimitProperties.GAME_ACTION;
        }
        if (ROOM_CHAT.matcher(destination).matches() || LOBBY_CHAT.equals(destination)) {
            return RateLimitProperties.CHAT;
        }
        if (ROOM_REACTION.matcher(destination).matches()) {
            return RateLimitProperties.REACTION;
        }
        return RateLimitProperties.STOMP_DEFAULT;
    }

    /** 액션이 막혔을 때만 본인 큐로 알린다 — 게임이 멈춘 이유를 클라가 표시할 수 있게. */
    private void notifyIfAction(String destination, long userId) {
        Matcher m = ROOM_ACTION.matcher(destination);
        if (!m.matches()) {
            return;
        }
        String roomId = m.group(1);
        var envelope = StompEnvelope.of("ERROR",
                Map.of("code", "RATE_LIMITED",
                        "message", "요청이 너무 빠릅니다. 잠시 후 다시 시도하세요."),
                clock);
        publisher.publishToUser(userId, "/queue/room/" + roomId, envelope);
    }
}
