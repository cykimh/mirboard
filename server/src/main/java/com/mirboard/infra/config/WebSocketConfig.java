package com.mirboard.infra.config;

import com.mirboard.infra.ws.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;
    private final SecurityProperties securityProperties;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
                           SecurityProperties securityProperties) {
        this.authInterceptor = authInterceptor;
        this.securityProperties = securityProperties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/user/queue");
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
        registration.interceptors(authInterceptor);
    }
}
