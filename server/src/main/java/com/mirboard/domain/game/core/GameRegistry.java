package com.mirboard.domain.game.core;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Spring 이 수집한 모든 {@link GameDefinition} 빈을 ID로 인덱싱한다. 카탈로그의
 * 진실 공급원이며, 새 게임은 본 클래스를 수정하지 않고 도메인 패키지에 새
 * GameDefinition 구현체를 추가하는 것만으로 자동 노출된다.
 */
@Component
public class GameRegistry {

    private static final Comparator<GameDefinition> CATALOG_ORDER =
            Comparator.comparing(GameDefinition::status)
                    .thenComparing(GameDefinition::displayName, Comparator.naturalOrder());

    private final Map<String, GameDefinition> byId;

    public GameRegistry(List<GameDefinition> defs) {
        Map<String, GameDefinition> map = new LinkedHashMap<>();
        for (GameDefinition d : defs) {
            if (map.putIfAbsent(d.id(), d) != null) {
                throw new IllegalStateException("Duplicate GameDefinition id: " + d.id());
            }
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    /** UI에 보일 카탈로그: AVAILABLE 우선, 그 안에서 displayName 순. DISABLED 는 제외. */
    public List<GameDefinition> catalog() {
        return byId.values().stream()
                .filter(d -> d.status() != GameStatus.DISABLED)
                .sorted(CATALOG_ORDER)
                .toList();
    }

    /** 실제로 플레이 가능한 게임만. */
    public List<GameDefinition> available() {
        return byId.values().stream()
                .filter(d -> d.status() == GameStatus.AVAILABLE)
                .sorted(CATALOG_ORDER)
                .toList();
    }

    public Optional<GameDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public GameDefinition require(String id) {
        var def = byId.get(id);
        if (def == null || def.status() == GameStatus.DISABLED) {
            throw new GameNotFoundException(id);
        }
        return def;
    }
}
