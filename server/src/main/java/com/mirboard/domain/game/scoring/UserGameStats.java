package com.mirboard.domain.game.scoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * D-115 — 한 유저의 한 게임 전적. {@code users} 화이트리스트(D-02) 밖에 둔다.
 * 쓰기는 {@link UserGameStatsRepository#record} 의 upsert 한 경로뿐이라 세터가 없다.
 */
@Entity
@Table(name = "user_game_stats")
@IdClass(UserGameStats.Pk.class)
public class UserGameStats {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "game_type", length = 32)
    private String gameType;

    @Column(nullable = false)
    private int rating;

    @Column(name = "win_count", nullable = false)
    private int winCount;

    @Column(name = "lose_count", nullable = false)
    private int loseCount;

    @Column(name = "desert_count", nullable = false)
    private int desertCount;

    protected UserGameStats() {
    }

    public Long getUserId() {
        return userId;
    }

    public String getGameType() {
        return gameType;
    }

    public int getRating() {
        return rating;
    }

    public int getWinCount() {
        return winCount;
    }

    public int getLoseCount() {
        return loseCount;
    }

    public int getDesertCount() {
        return desertCount;
    }

    public static class Pk implements Serializable {
        private Long userId;
        private String gameType;

        public Pk() {
        }

        public Pk(Long userId, String gameType) {
            this.userId = userId;
            this.gameType = gameType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return Objects.equals(userId, other.userId) && Objects.equals(gameType, other.gameType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, gameType);
        }
    }
}
