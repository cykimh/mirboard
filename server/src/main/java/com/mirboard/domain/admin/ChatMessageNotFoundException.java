package com.mirboard.domain.admin;

/**
 * D-93 — 신고 대상 메시지를 서버 링버퍼에서 못 찾음. 404 CHAT_MESSAGE_NOT_FOUND 로 매핑.
 *
 * <p>대부분은 "너무 오래된 메시지"다 — 링버퍼는 최근 100개 · TTL 2h 라서 그보다 오래된
 * 메시지는 신고할 수 없다. 잘못된 eventId 도 같은 경로로 떨어진다.
 */
public class ChatMessageNotFoundException extends RuntimeException {
    public ChatMessageNotFoundException(String eventId) {
        super("Chat message not found or expired: " + eventId);
    }
}
