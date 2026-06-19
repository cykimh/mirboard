package com.mirboard.domain.lobby.room;

/** D-81 — 판돈(stake>0) 방은 봇 채우기를 허용하지 않는다(봇=무한 잔액 → 칩 파밍 방지). */
public final class StakedRoomNoBotsException extends RuntimeException {
    public StakedRoomNoBotsException() {
        super("Staked rooms cannot be filled with bots");
    }
}
