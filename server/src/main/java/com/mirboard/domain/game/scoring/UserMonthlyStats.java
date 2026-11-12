package com.mirboard.domain.game.scoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * D-209 — 한 유저의 한 게임(또는 전체 {@link #ALL_GAMES}) 한 달 전적. 쓰기는 {@link UserMonthlyStatsRepository#record} 의
 * upsert 한 경로뿐이라 세터가 없다.
 */
@Entity
@Table(name = "user_monthly_stats")
@IdClass(UserMonthlyStats.Pk.class)
public class UserMonthlyStats {

    /** 모든 게임 합 행의 {@code game_type}. 게임 id 로 쓸 수 없는 글자다. */
    public static final String ALL_GAMES = "*";

    /** {@code recent} 에 남기는 결과 수. */
    public static final int RECENT_LIMIT = 5;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "game_type", length = 32)
    private String gameType;

    @Id
    @Column(name = "stat_month")
    private LocalDate statMonth;

    @Column(name = "win_count", nullable = false)
    private int winCount;

    @Column(name = "lose_count", nullable = false)
    private int loseCount;

    @Column(name = "desert_count", nullable = false)
    private int desertCount;

    /** 오래된 것 → 최근, 최대 5글자(W·L·D). */
    @Column(nullable = false, length = RECENT_LIMIT)
    private String recent;

    protected UserMonthlyStats() {
    }

    public Long getUserId() {
        return userId;
    }

    public String getGameType() {
        return gameType;
    }

    public LocalDate getStatMonth() {
        return statMonth;
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

    public String getRecent() {
        return recent;
    }

    public static class Pk implements Serializable {
        private Long userId;
        private String gameType;
        private LocalDate statMonth;

        public Pk() {
        }

        public Pk(Long userId, String gameType, LocalDate statMonth) {
            this.userId = userId;
            this.gameType = gameType;
            this.statMonth = statMonth;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return Objects.equals(userId, other.userId) && Objects.equals(gameType, other.gameType)
                    && Objects.equals(statMonth, other.statMonth);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, gameType, statMonth);
        }
    }
}
