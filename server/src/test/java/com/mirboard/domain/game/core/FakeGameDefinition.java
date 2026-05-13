package com.mirboard.domain.game.core;

/**
 * Test-only stub implementation of {@link GameDefinition}.
 */
final class FakeGameDefinition implements GameDefinition {

    private final String id;
    private final String displayName;
    private final GameStatus status;

    FakeGameDefinition(String id, String displayName, GameStatus status) {
        this.id = id;
        this.displayName = displayName;
        this.status = status;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String shortDescription() {
        return "(test stub)";
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        throw new UnsupportedOperationException("Fake — no engine");
    }
}
