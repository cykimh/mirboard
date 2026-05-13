package com.mirboard.domain.game.tichu;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import org.springframework.stereotype.Component;

@Component
public final class TichuGameDefinition implements GameDefinition {

    public static final String ID = "TICHU";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "티츄";
    }

    @Override
    public String shortDescription() {
        return "4인 파트너 카드 게임. 56장 덱과 4장의 특수 카드(마작·개·봉황·용).";
    }

    @Override
    public int minPlayers() {
        return 4;
    }

    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public GameStatus status() {
        return GameStatus.AVAILABLE;
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        throw new UnsupportedOperationException(
                "TichuEngine will be implemented in Phase 3 (current chunk only exposes metadata)");
    }
}
