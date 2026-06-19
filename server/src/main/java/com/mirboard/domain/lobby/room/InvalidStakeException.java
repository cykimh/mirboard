package com.mirboard.domain.lobby.room;

/** D-81 — 허용되지 않은 판돈(가상 칩) 값으로 방을 만들려 할 때. allowlist 외/음수 차단. */
public final class InvalidStakeException extends RuntimeException {
    private final int stake;

    public InvalidStakeException(int stake) {
        super("Invalid stake: " + stake);
        this.stake = stake;
    }

    public int stake() {
        return stake;
    }
}
