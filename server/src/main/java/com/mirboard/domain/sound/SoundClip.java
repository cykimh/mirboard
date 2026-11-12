package com.mirboard.domain.sound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * D-265 — 운영자가 올린 게임 음성 클립. 키({@link SoundClipKey})마다 한 개, 올리면 덮어쓴다.
 */
@Entity
@Table(name = "sound_clips")
public class SoundClip {

    @Id
    @Column(name = "clip_key", length = 64)
    private String clipKey;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    // PostgreSQL bytea 로 명시 — 기본 byte[] 매핑(oid)이면 ddl-auto:validate 가 V20 의 BYTEA 와 어긋나 기동이 실패한다.
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SoundClip() {
        // for JPA
    }

    public SoundClip(String clipKey, String contentType, byte[] data, Instant updatedAt) {
        this.clipKey = clipKey;
        this.contentType = contentType;
        this.data = data;
        this.byteSize = data.length;
        this.updatedAt = updatedAt;
    }

    public String getClipKey() {
        return clipKey;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getData() {
        return data;
    }

    public int getByteSize() {
        return byteSize;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
