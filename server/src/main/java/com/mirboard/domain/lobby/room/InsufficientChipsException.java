package com.mirboard.domain.lobby.room;

/** D-81 — 판돈 방에서 잔액이 판돈보다 적어 ready(=내기 참가)할 수 없을 때. */
public final class InsufficientChipsException extends RuntimeException {
    private final String roomId;
    private final long required;
    private final long balance;

    public InsufficientChipsException(String roomId, long required, long balance) {
        super("Insufficient chips: need " + required + " have " + balance);
        this.roomId = roomId;
        this.required = required;
        this.balance = balance;
    }

    public String roomId() {
        return roomId;
    }

    public long required() {
        return required;
    }

    public long balance() {
        return balance;
    }
}
