package com.mirboard.domain.lobby.room;

public final class RoomFullException extends RuntimeException {
    private final String roomId;

    public RoomFullException(String roomId) {
        super("Room is full: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
