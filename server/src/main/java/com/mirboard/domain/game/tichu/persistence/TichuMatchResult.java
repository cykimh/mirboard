package com.mirboard.domain.game.tichu.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tichu_match_results")
public class TichuMatchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    @Column(name = "finished_at", nullable = false, updatable = false)
    private Instant finishedAt;

    @Column(name = "team_a_score", nullable = false)
    private int teamAScore;

    @Column(name = "team_b_score", nullable = false)
    private int teamBScore;

    @Column(name = "payload_json", nullable = false, columnDefinition = "JSON")
    private String payloadJson;

    protected TichuMatchResult() {
    }

    public TichuMatchResult(String roomId, Instant finishedAt, int teamAScore, int teamBScore,
                            String payloadJson) {
        this.roomId = roomId;
        this.finishedAt = finishedAt;
        this.teamAScore = teamAScore;
        this.teamBScore = teamBScore;
        this.payloadJson = payloadJson;
    }

    public Long getId() {
        return id;
    }

    public String getRoomId() {
        return roomId;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getTeamAScore() {
        return teamAScore;
    }

    public int getTeamBScore() {
        return teamBScore;
    }

    public String getPayloadJson() {
        return payloadJson;
    }
}
