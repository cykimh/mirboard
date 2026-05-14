package com.mirboard.infra.messaging;

/**
 * 인스턴스 간에 전달되는 STOMP 라우팅 wrapper. {@link MessageGateway} 위에서 JSON
 * 으로 직렬화되어 흐른다. {@link StompMessageRelay} 가 수신 후 자신의
 * {@code SimpMessagingTemplate} 으로 재발행.
 *
 * @param destination 예: "/topic/room/abc", "/queue/room/abc"
 * @param userId      null 이면 공개 토픽 발행, 값이 있으면 해당 user 의 queue
 * @param envelope    STOMP envelope (JSON 으로 그대로 직렬화될 임의 객체)
 */
public record StompRoute(String destination, Long userId, Object envelope) {

    public static StompRoute topic(String destination, Object envelope) {
        return new StompRoute(destination, null, envelope);
    }

    public static StompRoute toUser(long userId, String destination, Object envelope) {
        return new StompRoute(destination, userId, envelope);
    }
}
