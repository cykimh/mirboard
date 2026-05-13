package com.mirboard.infra.rest.games;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameNotFoundException;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameCatalogController {

    private final GameRegistry registry;

    public GameCatalogController(GameRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public CatalogResponse list() {
        return new CatalogResponse(registry.catalog().stream().map(GameSummary::of).toList());
    }

    @GetMapping("/{id}")
    public GameSummary get(@PathVariable String id) {
        return GameSummary.of(
                Optional.ofNullable(id)
                        .flatMap(registry::find)
                        .filter(d -> d.status() != GameStatus.DISABLED)
                        .orElseThrow(() -> new GameNotFoundException(id)));
    }

    public record CatalogResponse(List<GameSummary> games) {
    }

    public record GameSummary(
            String id,
            String displayName,
            String shortDescription,
            int minPlayers,
            int maxPlayers,
            GameStatus status) {

        static GameSummary of(GameDefinition d) {
            return new GameSummary(
                    d.id(),
                    d.displayName(),
                    d.shortDescription(),
                    d.minPlayers(),
                    d.maxPlayers(),
                    d.status());
        }
    }
}
