package com.mirboard.domain.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * D-93 — 신고된 채팅 메시지 스냅샷. 상시 채팅 로그가 아니라 **신고된 것만** 영속된다
 * (원문은 Redis 링버퍼에 TTL 2h 로만 존재). users 화이트리스트 불변(D-02).
 *
 * <p>{@code message} 는 broadcast 된 본문 그대로다 — 즉 D-86 금칙어 마스킹이 이미
 * 적용된 상태다. 어드민이 보는 것과 사용자가 본 것을 일치시키기 위함.
 */
@Entity
@Table(name = "chat_reports")
public class ChatReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "scope", nullable = false, length = 8)
    private String scope;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "reported_user_id", nullable = false)
    private Long reportedUserId;

    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "message_at", nullable = false)
    private Instant messageAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatReport() {
    }

    public ChatReport(String eventId, String scope, String roomId, Long reportedUserId,
                      Long reporterUserId, String message, Instant messageAt, Instant createdAt) {
        this.eventId = eventId;
        this.scope = scope;
        this.roomId = roomId;
        this.reportedUserId = reportedUserId;
        this.reporterUserId = reporterUserId;
        this.message = message;
        this.messageAt = messageAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getScope() {
        return scope;
    }

    public String getRoomId() {
        return roomId;
    }

    public Long getReportedUserId() {
        return reportedUserId;
    }

    public Long getReporterUserId() {
        return reporterUserId;
    }

    public String getMessage() {
        return message;
    }

    public Instant getMessageAt() {
        return messageAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
