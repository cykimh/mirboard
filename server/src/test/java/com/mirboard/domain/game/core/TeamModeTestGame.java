package com.mirboard.domain.game.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D-176 — 맵 방 옵션을 선언하는 테스트 전용 게임 정의(오목 자리를 대신한다 — 오목 패키지와 무관). {@code TEAM_MODE}·{@code BOT_LEVEL}
 * 을 선언하고, 정원·시작 인원은 모드에 따라 2(1:1) 또는 4(2:2)로 같다 — 3명 시작은 안 된다. 엔진은 만들지 않고 받은
 * {@link GameContext} 만 기록한다(E3 확인용).
 */
public final class TeamModeTestGame implements GameDefinition {

    public static final String ID = "TEAM_MODE_TEST";

    /** 마지막으로 {@link #newEngine} 에 들어온 컨텍스트. */
    public final AtomicReference<GameContext> lastContext = new AtomicReference<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "모드 테스트 게임";
    }

    @Override
    public String shortDescription() {
        return "(D-176 테스트 스탠드인 — 1:1 또는 2:2)";
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
        return GameStatus.AVAILABLE;
    }

    @Override
    public Set<RoomOption> supportedRoomOptions() {
        return EnumSet.of(RoomOption.TEAM_MODE, RoomOption.BOT_LEVEL);
    }

    @Override
    public int capacityFor(Map<RoomOption, String> options) {
        return "TEAMS_2V2".equals(options.get(RoomOption.TEAM_MODE)) ? 4 : 2;
    }

    @Override
    public int playersToStart(Map<RoomOption, String> options) {
        return capacityFor(options);
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        lastContext.set(ctx);
        throw new UnsupportedOperationException("테스트 스탠드인 — 엔진 없음");
    }
}
