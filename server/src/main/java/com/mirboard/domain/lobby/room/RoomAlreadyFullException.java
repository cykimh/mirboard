package com.mirboard.domain.lobby.room;

/** D-136 — 정원이 다 찬 방은 "지금 시작"이 아니라 전원 준비로 시작한다(줄일 정원이 없다). */
public final class RoomAlreadyFullException extends RuntimeException {
    private final String roomId;

    public RoomAlreadyFullException(String roomId) {
        super("Room is already full; start by getting everyone ready: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
