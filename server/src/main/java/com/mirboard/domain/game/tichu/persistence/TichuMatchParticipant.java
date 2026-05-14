package com.mirboard.domain.game.tichu.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "tichu_match_participants")
@IdClass(TichuMatchParticipant.Pk.class)
public class TichuMatchParticipant {

    @Id
    @Column(name = "match_id")
    private Long matchId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 1)
    private String team;

    @Column(name = "is_win", nullable = false)
    private boolean isWin;

    protected TichuMatchParticipant() {
    }

    public TichuMatchParticipant(long matchId, long userId, String team, boolean isWin) {
        if (!"A".equals(team) && !"B".equals(team)) {
            throw new IllegalArgumentException("team must be 'A' or 'B': " + team);
        }
        this.matchId = matchId;
        this.userId = userId;
        this.team = team;
        this.isWin = isWin;
    }

    public Long getMatchId() {
        return matchId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTeam() {
        return team;
    }

    public boolean isWin() {
        return isWin;
    }

    public static class Pk implements Serializable {
        private Long matchId;
        private Long userId;

        public Pk() {
        }

        public Pk(Long matchId, Long userId) {
            this.matchId = matchId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return Objects.equals(matchId, other.matchId) && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(matchId, userId);
        }
    }
}
