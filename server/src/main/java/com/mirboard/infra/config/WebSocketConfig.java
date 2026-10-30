package com.mirboard.infra.config;

import com.mirboard.infra.ratelimit.StompRateLimitInterceptor;
import com.mirboard.infra.ws.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** D-158 — STOMP 하트비트(ms, 서버 송신·수신 기대). 클라 두 소켓도 같은 값을 청한다. */
    static final long HEARTBEAT_MS = 10_000;

    private final StompAuthChannelInterceptor authInterceptor;
    private final StompRateLimitInterceptor rateLimitInterceptor;
    private final SecurityProperties securityProperties;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
                           StompRateLimitInterceptor rateLimitInterceptor,
                           SecurityProperties securityProperties) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.securityProperties = securityProperties;
    }

    private TaskScheduler messageBrokerTaskScheduler;

    /**
     * D-158 — 단순 브로커의 하트비트는 스케줄러가 있어야 돈다. Spring 의 {@code messageBrokerTaskScheduler} 를 쓴다(이 설정이 그
     * 빈을 만드는 설정에 들어가므로 순환을 피해 {@code @Lazy}). 새 TaskScheduler 빈을 만들면 타입으로 주입받는 다른 곳이 모호해진다.
     */
    @Autowired
    public void setMessageBrokerTaskScheduler(@Lazy TaskScheduler messageBrokerTaskScheduler) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // D-126 — 사용자 목적지(`/user/queue/x`)는 Spring 이 `/queue/x-user{세션}` 으로 바꿔 브로커에
        // 넘긴다. 브로커를 `/user/queue` 로만 열어 두었을 땐 그 목적지가 버려져 본인 큐(손패·받은 카드·
        // ERROR)가 한 번도 배달되지 않았다 — 티츄 매 플레이 resync 가 손패를 우연히 고쳐 가렸다.
        // `/queue` 직접 구독은 StompAuthChannelInterceptor 가 막는다(남의 세션 큐 엿보기 방지).
        // D-158 — 하트비트를 켠다. 꺼져 있으면(CONNECTED heart-beat 0,0) 망이 바뀌어 죽은 소켓을 클라가 몰라 "연결됨"인 채로 멈추고,
        // 서버도 끊김을 늦게 알아 탈주 유예가 늦게 걸린다. 프록시·NAT 의 유휴 끊김도 막는다.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(messageBrokerTaskScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // D-83 — 핸드셰이크 origin 을 화이트리스트로 고정(전면 개방 폐지).
        String[] origins = securityProperties.allowedOrigins().toArray(String[]::new);
        // Raw WS endpoint for native @stomp/stompjs clients.
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
        // SockJS fallback for environments without native WS support.
        registry.addEndpoint("/ws").setAllowedOrigins(origins).withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // D-90 — 순서 고정: auth 가 먼저여야 CONNECT 에서 Principal 이 세팅되고,
        // 그 뒤에 레이트리밋이 userId 를 키로 쓸 수 있다.
        registration.interceptors(authInterceptor, rateLimitInterceptor);
    }
}
