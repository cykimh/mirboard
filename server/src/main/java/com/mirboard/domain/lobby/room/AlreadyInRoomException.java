package com.mirboard.domain.lobby.room;

public final class AlreadyInRoomException extends RuntimeException {
    private final String roomId;

    public AlreadyInRoomException(String roomId) {
        super("User already in room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
