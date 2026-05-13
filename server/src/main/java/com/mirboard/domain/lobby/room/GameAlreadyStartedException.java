package com.mirboard.domain.lobby.room;

public final class GameAlreadyStartedException extends RuntimeException {
    private final String roomId;

    public GameAlreadyStartedException(String roomId) {
        super("Game already started in room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
