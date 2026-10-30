package com.mirboard.domain.lobby.room;

import java.util.List;
import java.util.Set;

public record Room(
        String roomId,
        String name,
        String gameType,
        long hostId,
        RoomStatus status,
        int capacity,
        int playerCount,
        List<Long> playerIds,
        Set<Long> spectatorIds,
        TeamPolicy teamPolicy,
        long createdAt,
        boolean fillWithBots,
        List<Integer> botSeats,
        int targetScore,
        int turnSeconds,
        // D-81 — 판돈(가상 칩). 0=내기 없음. 생성 시 고정(불변).
        int stake,
        // Phase 16(#2) — 대기실에서 준비 완료한 플레이어 id 집합.
        Set<Long> readyUserIds,
        // D-161 — 원카드 경쟁 버튼 크기(SMALL·MEDIUM·LARGE). 방 설정(RoomOption.RACE_BUTTON_SIZE). 없으면 MEDIUM.
        String raceButtonSize,
        // D-165 — 원카드 경쟁 버튼 찾기 난이도(NORMAL·HARD·VERY_HARD). 방 설정(RACE_BUTTON_VISIBILITY). 없으면 NORMAL.
        String raceButtonVisibility) {

    /** D-161 — 버튼 크기 기본값과 허용값. */
    public static final String DEFAULT_RACE_BUTTON_SIZE = "MEDIUM";
    public static final Set<String> RACE_BUTTON_SIZES = Set.of("SMALL", "MEDIUM", "LARGE");
    /** D-165 — 찾기 난이도 기본값과 허용값. */
    public static final String DEFAULT_RACE_BUTTON_VISIBILITY = "NORMAL";
    public static final Set<String> RACE_BUTTON_VISIBILITIES = Set.of("NORMAL", "HARD", "VERY_HARD");

    /** D-161 이전 모양(버튼 크기·찾기 난이도 없음) — 기본 MEDIUM·NORMAL. */
    public Room(String roomId, String name, String gameType, long hostId, RoomStatus status, int capacity,
                int playerCount, List<Long> playerIds, Set<Long> spectatorIds, TeamPolicy teamPolicy, long createdAt,
                boolean fillWithBots, List<Integer> botSeats, int targetScore, int turnSeconds, int stake,
                Set<Long> readyUserIds) {
        this(roomId, name, gameType, hostId, status, capacity, playerCount, playerIds, spectatorIds, teamPolicy,
                createdAt, fillWithBots, botSeats, targetScore, turnSeconds, stake, readyUserIds,
                DEFAULT_RACE_BUTTON_SIZE, DEFAULT_RACE_BUTTON_VISIBILITY);
    }

    /** 관전자 추가/제거 같이 spectatorIds 만 다른 사본을 만들 때 사용. */
    public Room withSpectatorIds(Set<Long> newSpectatorIds) {
        return new Room(roomId, name, gameType, hostId, status, capacity, playerCount,
                playerIds, newSpectatorIds, teamPolicy, createdAt, fillWithBots, botSeats,
                targetScore, turnSeconds, stake, readyUserIds, raceButtonSize, raceButtonVisibility);
    }
}
