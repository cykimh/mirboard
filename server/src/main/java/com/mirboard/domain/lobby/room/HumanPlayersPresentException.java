package com.mirboard.domain.lobby.room;

/**
 * D-135 — 방장 강제 종료는 방장 말고 모두 봇인 판에서만이다. 사람이 더 있으면 거절한다(강제 종료는 매치 기록·레이팅을 남기지 않아
 * 사람 판에서는 판 깨기에 쓰일 수 있다).
 */
public final class HumanPlayersPresentException extends RuntimeException {
    private final String roomId;

    public HumanPlayersPresentException(String roomId) {
        super("Host abort is only allowed when every other player is a bot: " + roomId);
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
