package com.mirboard.domain.lobby.room;

/** D-136 — "지금 N명으로 시작"은 방장 말고 모두 준비했을 때만이다(방장이 누르는 것을 방장의 준비로 친다). */
public final class PlayersNotReadyException extends RuntimeException {
    private final String roomId;

    public PlayersNotReadyException(String roomId) {
        super("Every player must be ready to start room: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
