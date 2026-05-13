package com.mirboard.domain.lobby.room;

public final class RoomNotFoundException extends RuntimeException {
    private final String roomId;

    public RoomNotFoundException(String roomId) {
        super("Room not found: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
