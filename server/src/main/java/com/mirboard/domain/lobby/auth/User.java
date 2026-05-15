package com.mirboard.domain.lobby.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "win_count", nullable = false)
    private int winCount;

    @Column(name = "lose_count", nullable = false)
    private int loseCount;

    /** Phase 8D — ELO 점수. 기본 1000 (V2 마이그레이션). 매치 결과로 +/- 갱신. */
    @Column(nullable = false)
    private int rating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    private User(String username, String passwordHash, Instant createdAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.winCount = 0;
        this.loseCount = 0;
        this.rating = 1000;
        this.createdAt = createdAt;
    }

    public static User create(String username, String passwordHash, Clock clock) {
        return new User(username, passwordHash, Instant.now(clock));
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getWinCount() {
        return winCount;
    }

    public int getLoseCount() {
        return loseCount;
    }

    public int getRating() {
        return rating;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
