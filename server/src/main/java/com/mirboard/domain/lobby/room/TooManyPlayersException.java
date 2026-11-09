package com.mirboard.domain.lobby.room;

/**
 * D-176 — 정원이 줄어드는 설정 변경(예: 2:2 → 1:1)인데 지금 앉은 인원이 새 정원보다 많다. 방장이 [봇 빼기] 등으로 줄인 뒤 바꾼다.
 */
public final class TooManyPlayersException extends RuntimeException {
    private final String roomId;
    private final int capacity;

    public TooManyPlayersException(String roomId, int capacity) {
        super("Too many players for new capacity " + capacity + " in room: " + roomId);
        this.roomId = roomId;
        this.capacity = capacity;
    }

    public String roomId() {
        return roomId;
    }

    /** 바꾸려던 정원. */
    public int capacity() {
        return capacity;
    }
}
