package com.mirboard.infra.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 다른 인스턴스 (또는 자신) 가 {@link MessageGateway} 로 발행한 {@link StompRoute}
 * 를 받아 본 인스턴스의 {@code SimpMessagingTemplate} 으로 재발행.
 *
 * <p>채널 prefix: {@code stomp:routes}. {@link StompPublisher} 도 같은 prefix 로
 * publish.
 *
 * <p>구독자/사용자가 어느 인스턴스에 붙어 있든 자신의 인스턴스 broker 가 STOMP
 * 프레임을 전달하므로 sticky session 이 없어도 작동한다.
 */
@Component
public class StompMessageRelay {

    public static final String STOMP_CHANNEL = "stomp:routes";

    private static final Logger log = LoggerFactory.getLogger(StompMessageRelay.class);

    private final SimpMessagingTemplate broker;
    private final MessageGateway gateway;
    private final ObjectMapper objectMapper;

    public StompMessageRelay(SimpMessagingTemplate broker,
                             MessageGateway gateway,
                             ObjectMapper objectMapper) {
        this.broker = broker;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        gateway.subscribe(STOMP_CHANNEL, (channel, payload) -> dispatch(payload));
    }

    private void dispatch(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String destination = root.get("destination").asText();
            JsonNode userIdNode = root.get("userId");
            Object envelope = objectMapper.treeToValue(root.get("envelope"), Object.class);

            if (userIdNode == null || userIdNode.isNull()) {
                broker.convertAndSend(destination, envelope);
            } else {
                broker.convertAndSendToUser(userIdNode.asText(), destination, envelope);
            }
        } catch (Exception e) {
            log.warn("Failed to dispatch STOMP route: {}", e.getMessage());
        }
    }
}
