package com.mirboard.domain.lobby.room;

import java.util.List;

public record Room(
        String roomId,
        String name,
        String gameType,
        long hostId,
        RoomStatus status,
        int capacity,
        int playerCount,
        List<Long> playerIds,
        long createdAt) {
}
