package com.mirboard.infra.ws;

/** D-162 — 내보내려는 사람이 아직 그 방에 연결돼 있다. 끊긴 사람만 내보낼 수 있다. */
public final class PlayerStillConnectedException extends RuntimeException {

    private final long userId;

    public PlayerStillConnectedException(long userId) {
        super("Player is still connected: " + userId);
        this.userId = userId;
    }

    public long userId() {
        return userId;
    }
}
