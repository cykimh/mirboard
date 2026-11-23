package com.mirboard.domain.lobby.room;

/** D-290 — 방장이 방금 대기실에서 내보낸 사람이 그 방에 다시 들어오려 했다(재입장 금지가 풀릴 때까지 앉기·관전 모두 거절). */
public final class KickedFromRoomException extends RuntimeException {
    private final String roomId;

    public KickedFromRoomException(String roomId) {
        super("User was kicked from room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
