package com.mirboard.domain.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * D-86 — 어드민 역할(권한을 users 에 두지 않음, 규칙#3). {@code userId} 가 PK 이자 users(id) FK.
 */
@Entity
@Table(name = "admin_roles")
public class AdminRole {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    protected AdminRole() {
    }

    public AdminRole(Long userId, Instant grantedAt) {
        this.userId = userId;
        this.grantedAt = grantedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
