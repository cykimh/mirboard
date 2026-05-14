package com.mirboard.infra.messaging;

import java.util.function.BiConsumer;

/**
 * 인스턴스 간 메시지 전달 추상 (Phase 6D-1).
 *
 * <p>단일 인스턴스 모드에선 {@link InMemoryMessageGateway} 가 같은 JVM 안에서
 * 콜백을 직접 호출. 멀티 인스턴스 모드에선 {@link RedisMessageGateway} 가 Redis
 * Pub/Sub 으로 fan-out. Spring 의 {@code SimpMessagingTemplate} 는 인스턴스 내부
 * SimpleBroker 에만 메시지를 보내므로, 다른 인스턴스의 STOMP 구독자에게 전달하려면
 * 본 gateway 를 거쳐야 한다.
 *
 * <p>채널 이름 규약 (관례):
 * <ul>
 *   <li>{@code stomp:topic:<destination>} — 공개 STOMP 토픽 broadcast</li>
 *   <li>{@code stomp:user:<userId>:<destination>} — 본인 큐 전달</li>
 *   <li>{@code domain:event:<type>} — ApplicationEvent 의 클러스터 fan-out</li>
 * </ul>
 *
 * <p>payload 는 UTF-8 JSON 문자열. 구현체는 직렬화/역직렬화에 관여하지 않음.
 */
public interface MessageGateway {

    /** 채널에 payload 를 발행. 본 인스턴스 + (Redis 구현이면) 다른 인스턴스 모두 수신. */
    void publish(String channel, String payload);

    /**
     * 채널 또는 패턴 (Redis psubscribe) 을 구독. handler 의 첫 인자는 실제 channel,
     * 두 번째는 payload. 같은 채널을 여러 번 구독하면 모든 handler 가 호출됨.
     */
    void subscribe(String pattern, BiConsumer<String, String> handler);
}
