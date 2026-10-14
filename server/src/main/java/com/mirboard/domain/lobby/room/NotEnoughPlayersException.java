package com.mirboard.domain.lobby.room;

/** D-136 — "지금 N명으로 시작"은 게임 최소 인원 이상일 때만이다. */
public final class NotEnoughPlayersException extends RuntimeException {
    private final String roomId;
    private final int minPlayers;

    public NotEnoughPlayersException(String roomId, int minPlayers) {
        super("At least " + minPlayers + " players are needed to start room: " + roomId);
        this.roomId = roomId;
        this.minPlayers = minPlayers;
    }

    public String roomId() {
        return roomId;
    }

    public int minPlayers() {
        return minPlayers;
    }
}
