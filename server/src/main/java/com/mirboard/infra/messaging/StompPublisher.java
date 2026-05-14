package com.mirboard.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STOMP 라우팅 발행자 — {@link SimpMessagingTemplate} 을 직접 호출하지 않고 본 클래스를
 * 거쳐 {@link MessageGateway} 위에서 흐르게 한다. {@link StompMessageRelay} 가 모든
 * 인스턴스에서 받아 자신의 broker 로 재발행.
 */
@Component
public class StompPublisher {

    private static final Logger log = LoggerFactory.getLogger(StompPublisher.class);

    private final MessageGateway gateway;
    private final ObjectMapper objectMapper;

    public StompPublisher(MessageGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public void publishToTopic(String destination, Object envelope) {
        publish(StompRoute.topic(destination, envelope));
    }

    public void publishToUser(long userId, String destination, Object envelope) {
        publish(StompRoute.toUser(userId, destination, envelope));
    }

    private void publish(StompRoute route) {
        try {
            String json = objectMapper.writeValueAsString(route);
            gateway.publish(StompMessageRelay.STOMP_CHANNEL, json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize STOMP route: {}", e.getMessage());
        }
    }
}
