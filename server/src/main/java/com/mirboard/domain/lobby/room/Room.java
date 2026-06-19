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
        Set<Long> readyUserIds) {

    /** 관전자 추가/제거 같이 spectatorIds 만 다른 사본을 만들 때 사용. */
    public Room withSpectatorIds(Set<Long> newSpectatorIds) {
        return new Room(roomId, name, gameType, hostId, status, capacity, playerCount,
                playerIds, newSpectatorIds, teamPolicy, createdAt, fillWithBots, botSeats,
                targetScore, turnSeconds, stake, readyUserIds);
    }
}
