package com.mirboard.domain.lobby.room;

public final class ResyncNotAvailableException extends RuntimeException {

    private final String roomId;

    public ResyncNotAvailableException(String roomId) {
        super("No active game state to resync for room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
