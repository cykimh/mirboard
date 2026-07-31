package com.mirboard.domain.game.skullking;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 스컬킹의 카탈로그 메타데이터 + 엔진 팩토리 (D-102). {@code GameRegistry} 가 자동
 * 수집한다 — 로비/허브/디스패치 수정 없이 이 Bean 등록으로 인게임까지 연결된다
 * (D-06/D-11/D-98 의 약속).
 *
 * <p>인원 2~8 가변 — 방 만들기의 capacity 선택(D-99)이 처음으로 실사용된다.
 */
@Component
public final class SkullKingGameDefinition implements GameDefinition {

    public static final String ID = "SKULL_KING";

    private final SkullKingStateStore stateStore;
    private final SkullKingMatchStateStore matchStateStore;
    private final SecureRandom random = new SecureRandom();

    public SkullKingGameDefinition(SkullKingStateStore stateStore,
                                   SkullKingMatchStateStore matchStateStore) {
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "스컬킹";
    }

    @Override
    public String shortDescription() {
        return "2~8인 트릭테이킹. 매 라운드 자기 승수를 예측하고 정확히 맞혀야 점수를 얻는다.";
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public int maxPlayers() {
        return 8;
    }

    @Override
    public GameStatus status() {
        return GameStatus.AVAILABLE;
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new SkullKingGameEngine(ctx, stateStore, matchStateStore, random);
    }
}
