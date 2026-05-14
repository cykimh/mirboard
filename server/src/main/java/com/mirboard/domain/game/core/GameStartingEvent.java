package com.mirboard.domain.game.core;

import java.util.List;

/**
 * 방이 capacity 에 도달해 게임이 막 시작되어야 할 때 lobby 도메인이 발행하는 이벤트.
 * 각 게임 도메인은 본 이벤트를 {@code @EventListener} 로 받아 자신에게 해당하는
 * {@code gameType} 일 때만 라운드 초기화 로직을 실행한다 — lobby ↛ game 직접 의존을
 * 끊는 분리.
 */
public record GameStartingEvent(String roomId, String gameType, List<Long> playerIds) {

    public GameStartingEvent {
        playerIds = List.copyOf(playerIds);
    }
}
