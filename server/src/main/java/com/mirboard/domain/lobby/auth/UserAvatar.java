package com.mirboard.domain.lobby.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 선택적 코스메틱 아바타(D-80). {@code users} 와 분리된 테이블로, 식별 정보가 아닌
 * 사용자 설정 이미지를 128x128 PNG 바이트로 보관한다. user_id 가 PK(1:1).
 */
@Entity
@Table(name = "user_avatars")
public class UserAvatar {

    @Id
    @Column(name = "user_id")
    private Long userId;

    // PostgreSQL bytea 로 명시(기본 byte[] 가 oid/Large Object 로 매핑되면
    // ddl-auto:validate 가 V5 의 BYTEA 와 불일치로 기동 실패하므로 강제).
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false)
    private byte[] image;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAvatar() {
        // for JPA
    }

    public UserAvatar(Long userId, byte[] image, String contentType, Instant updatedAt) {
        this.userId = userId;
        this.image = image;
        this.contentType = contentType;
        this.updatedAt = updatedAt;
    }

    public void update(byte[] image, String contentType, Clock clock) {
        this.image = image;
        this.contentType = contentType;
        this.updatedAt = Instant.now(clock);
    }

    public Long getUserId() {
        return userId;
    }

    public byte[] getImage() {
        return image;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
