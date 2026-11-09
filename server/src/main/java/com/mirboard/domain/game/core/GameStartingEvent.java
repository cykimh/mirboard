package com.mirboard.domain.game.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 방이 capacity 에 도달해 게임이 막 시작되어야 할 때 lobby 도메인이 발행하는 이벤트.
 * 각 게임 도메인은 본 이벤트를 {@code @EventListener} 로 받아 자신에게 해당하는
 * {@code gameType} 일 때만 라운드 초기화 로직을 실행한다 — lobby ↛ game 직접 의존을
 * 끊는 분리.
 *
 * @param options D-176 — 이 게임이 선언한 맵 옵션 값({@link GameContext#options()} 와 같은 값). 라운드 시작이 방 설정을 읽는
 *                유일한 창구다(오목의 모드·룰). 옵션을 안 쓰는 게임은 빈 맵.
 */
public record GameStartingEvent(String roomId, String gameType, List<Long> playerIds,
                                int targetScore, Map<RoomOption, String> options) {

    public GameStartingEvent {
        playerIds = List.copyOf(playerIds);
        options = options == null || options.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new EnumMap<>(options));
    }

    /** D-176 이전 모양 — 방 옵션 없음. */
    public GameStartingEvent(String roomId, String gameType, List<Long> playerIds, int targetScore) {
        this(roomId, gameType, playerIds, targetScore, Map.of());
    }

    /** 맵 옵션 값. 게임이 선언하지 않은 옵션이면 null. */
    public String option(RoomOption option) {
        return options.get(option);
    }
}
