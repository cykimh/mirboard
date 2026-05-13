package com.mirboard.domain.lobby.room;

public final class NotInRoomException extends RuntimeException {
    private final String roomId;

    public NotInRoomException(String roomId) {
        super("User is not in room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
