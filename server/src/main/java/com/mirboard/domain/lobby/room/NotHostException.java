package com.mirboard.domain.lobby.room;

public final class NotHostException extends RuntimeException {
    private final String roomId;

    public NotHostException(String roomId) {
        super("User is not the host of room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
