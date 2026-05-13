package com.mirboard.domain.game.core;

public final class GameNotFoundException extends RuntimeException {

    private final String gameId;

    public GameNotFoundException(String gameId) {
        super("Game not found or not available: " + gameId);
        this.gameId = gameId;
    }

    public String gameId() {
        return gameId;
    }
}
