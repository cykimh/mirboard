package com.mirboard.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 도메인 이벤트를 인스턴스 내부 (Spring {@link ApplicationEventPublisher}) + 클러스터
 * 전파 (Redis Pub/Sub via {@link MessageGateway}) 양쪽으로 보낸다 (Phase 6D-3).
 *
 * <p>호출자는 {@link #publish(Object)} 만 사용. 자기 인스턴스의 {@code @EventListener}
 * 는 즉시 호출되고, 다른 인스턴스의 listener 들은 본 클래스가 gateway 위에서 흐른
 * JSON 을 받아 {@link ApplicationEventPublisher} 로 재발행한다.
 *
 * <p>중복 처리 방지: 메시지에 발행 인스턴스의 {@code instanceId} 를 부착하고, 수신
 * 측은 자기 인스턴스 발행분은 skip 한다 (in-process 호출에서 이미 처리됨).
 *
 * <p>호출 패턴 — 도메인 코드는 그냥 publish 만 부름:
 * <pre>{@code
 *   domainEvents.publish(new RoomChangedEvent(...));
 * }</pre>
 */
@Component
public class DomainEventBus {

    public static final String DOMAIN_EVENT_CHANNEL = "domain:event";

    private static final Logger log = LoggerFactory.getLogger(DomainEventBus.class);

    private final ApplicationEventPublisher localEvents;
    private final MessageGateway gateway;
    private final ObjectMapper objectMapper;
    private final String instanceId = UUID.randomUUID().toString();

    public DomainEventBus(ApplicationEventPublisher localEvents,
                          MessageGateway gateway,
                          ObjectMapper objectMapper) {
        this.localEvents = localEvents;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        gateway.subscribe(DOMAIN_EVENT_CHANNEL, (channel, payload) -> dispatch(payload));
    }

    /**
     * 도메인 이벤트 발행. 자기 인스턴스의 listener 즉시 호출 + 다른 인스턴스로
     * Redis Pub/Sub fan-out.
     */
    public void publish(Object event) {
        // 1) Local listener 즉시 호출.
        localEvents.publishEvent(event);
        // 2) 클러스터 전파 — JSON wrap.
        try {
            String json = objectMapper.writeValueAsString(new Envelope(
                    instanceId,
                    event.getClass().getName(),
                    event));
            gateway.publish(DOMAIN_EVENT_CHANNEL, json);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 클러스터 전파만 막고 in-process 는 이미 처리됨.
            log.warn("Failed to serialize domain event for cluster fanout: {}", e.getMessage());
        }
    }

    private void dispatch(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String fromInstance = root.get("instanceId").asText();
            if (instanceId.equals(fromInstance)) {
                // 본인이 발행한 메시지 — in-process 호출에서 이미 처리됨.
                return;
            }
            String className = root.get("eventClass").asText();
            Class<?> eventClass = Class.forName(className);
            Object event = objectMapper.treeToValue(root.get("event"), eventClass);
            localEvents.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to dispatch cluster domain event: {}", e.getMessage());
        }
    }

    /** wrapper — instanceId/eventClass/event 3-필드 JSON. */
    public record Envelope(String instanceId, String eventClass, Object event) {
    }
}
