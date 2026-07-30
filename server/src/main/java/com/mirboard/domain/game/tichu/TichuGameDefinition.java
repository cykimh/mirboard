package com.mirboard.domain.game.tichu;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.infra.messaging.DomainEventBus;
import org.springframework.stereotype.Component;

/**
 * 티츄의 카탈로그 메타데이터 + 엔진 팩토리.
 *
 * <p>D-98: {@link #newEngine} 이 드디어 호출부를 갖는다 — 인게임 디스패치가
 * {@code GameEngineProvider} → {@code GameRegistry.require(gameType).newEngine(ctx)} 로
 * 흐르므로, 티츄 어댑터가 필요한 저장소/이벤트버스를 여기서 주입받아 넘긴다.
 */
@Component
public final class TichuGameDefinition implements GameDefinition {

    public static final String ID = "TICHU";

    private final TichuGameStateStore stateStore;
    private final TichuMatchStateStore matchStateStore;
    private final TichuRoundStarter roundStarter;
    private final DomainEventBus events;

    public TichuGameDefinition(TichuGameStateStore stateStore,
                               TichuMatchStateStore matchStateStore,
                               TichuRoundStarter roundStarter,
                               DomainEventBus events) {
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
        this.roundStarter = roundStarter;
        this.events = events;
    }

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
        return new TichuGameEngine(ctx, stateStore, matchStateStore, roundStarter, events);
    }
}
