package com.mirboard.domain.lobby.room;

public final class GameNotInProgressException extends RuntimeException {
    private final String roomId;

    public GameNotInProgressException(String roomId) {
        super("Game is not in progress for room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
