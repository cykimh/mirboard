package com.mirboard.domain.lobby.room;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class RoomService {

    private final RoomRepository repository;
    private final GameRegistry games;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       ApplicationEventPublisher events) {
        this.repository = repository;
        this.games = games;
        this.clock = clock;
        this.events = events;
    }

    public Room createRoom(long hostUserId, String name, String gameType) {
        GameDefinition def = games.require(gameType);
        if (def.status() != GameStatus.AVAILABLE) {
            throw new com.mirboard.domain.game.core.GameNotFoundException(gameType);
        }
        String roomId = UUID.randomUUID().toString();
        long now = Instant.now(clock).toEpochMilli();
        repository.create(roomId, hostUserId, name, gameType, def.maxPlayers(), now);
        Room room = getRoom(roomId);
        events.publishEvent(RoomChangedEvent.updated(room));
        return room;
    }

    public Room getRoom(String roomId) {
        return repository.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
    }

    public Room joinRoom(String roomId, long userId) {
        long now = Instant.now(clock).toEpochMilli();
        repository.join(roomId, userId, now);
        Room room = getRoom(roomId);
        events.publishEvent(RoomChangedEvent.updated(room));
        return room;
    }

    public void leaveRoom(String roomId, long userId) {
        repository.leave(roomId, userId);
        Optional<Room> remaining = repository.findById(roomId);
        events.publishEvent(remaining
                .map(RoomChangedEvent::updated)
                .orElseGet(() -> RoomChangedEvent.destroyed(roomId)));
    }

    public List<Room> listWaitingRooms(String gameTypeFilter) {
        return repository.openRoomIds().stream()
                .map(repository::findById)
                .flatMap(Optional::stream)
                .filter(r -> gameTypeFilter == null || gameTypeFilter.equals(r.gameType()))
                .toList();
    }
}
