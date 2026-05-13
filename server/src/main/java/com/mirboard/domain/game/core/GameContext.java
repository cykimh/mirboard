package com.mirboard.domain.game.core;

import java.util.List;

/**
 * 게임 엔진 생성 시 전달되는 방 정보. Phase 3 에서 추가 필드가 들어올 수 있다.
 */
public record GameContext(String roomId, List<Long> playerIds) {
}
