package com.mirboard.domain.lobby.room;

/**
 * D-99 — 요청한 방 인원이 게임의 허용 범위를 벗어날 때. 범위는 게임이 정한다
 * ({@code GameDefinition.minPlayers()} ~ {@code maxPlayers()}). 티츄는 4~4 라
 * 4 외의 값이 전부 여기로 떨어진다.
 */
public final class InvalidCapacityException extends RuntimeException {

    private final int capacity;
    private final int minPlayers;
    private final int maxPlayers;

    public InvalidCapacityException(int capacity, int minPlayers, int maxPlayers) {
        super("Invalid capacity: " + capacity + " (allowed " + minPlayers + ".." + maxPlayers + ")");
        this.capacity = capacity;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
    }

    public int capacity() {
        return capacity;
    }

    public int minPlayers() {
        return minPlayers;
    }

    public int maxPlayers() {
        return maxPlayers;
    }
}
