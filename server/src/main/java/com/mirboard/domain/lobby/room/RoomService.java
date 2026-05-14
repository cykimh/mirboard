package com.mirboard.domain.lobby.room;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository repository;
    private final GameRegistry games;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final MirboardMetrics metrics;

    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       ApplicationEventPublisher events,
                       MirboardMetrics metrics) {
        this.repository = repository;
        this.games = games;
        this.clock = clock;
        this.events = events;
        this.metrics = metrics;
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
        metrics.roomCreated();
        log.info("Room created: roomId={} gameType={} hostUserId={} capacity={}",
                roomId, gameType, hostUserId, def.maxPlayers());
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
        metrics.roomJoined();
        log.info("Room join: roomId={} userId={} occupancy={}/{} status={}",
                roomId, userId, room.playerIds().size(), room.capacity(), room.status());
        if (room.status() == RoomStatus.IN_GAME) {
            events.publishEvent(new com.mirboard.domain.game.core.GameStartingEvent(
                    room.roomId(), room.gameType(), room.playerIds()));
            metrics.gameStarted();
            log.info("Game starting: roomId={} gameType={} players={}",
                    roomId, room.gameType(), room.playerIds());
        }
        return room;
    }

    public void leaveRoom(String roomId, long userId) {
        repository.leave(roomId, userId);
        Optional<Room> remaining = repository.findById(roomId);
        events.publishEvent(remaining
                .map(RoomChangedEvent::updated)
                .orElseGet(() -> RoomChangedEvent.destroyed(roomId)));
        log.info("Room leave: roomId={} userId={} destroyed={}",
                roomId, userId, remaining.isEmpty());
    }

    public void markFinished(String roomId) {
        repository.markFinished(roomId, Instant.now(clock).toEpochMilli());
        repository.findById(roomId)
                .ifPresent(room -> events.publishEvent(RoomChangedEvent.updated(room)));
        log.info("Room finished: roomId={}", roomId);
    }

    /** 관전 추가. 이미 플레이어로 입장한 사용자는 거절. */
    public Room spectate(String roomId, long userId) {
        Room room = getRoom(roomId);
        if (room.playerIds().contains(userId)) {
            throw new AlreadyInRoomException(roomId);
        }
        boolean added = repository.addSpectator(roomId, userId);
        log.info("Room spectate: roomId={} userId={} newcomer={}", roomId, userId, added);
        return getRoom(roomId);
    }

    /** 관전 종료. 등록 안 되어 있어도 idempotent. */
    public void stopSpectating(String roomId, long userId) {
        boolean removed = repository.removeSpectator(roomId, userId);
        log.info("Room stop spectating: roomId={} userId={} wasPresent={}",
                roomId, userId, removed);
    }

    /** 참여자 또는 관전자 여부. */
    public boolean isParticipantOrSpectator(String roomId, long userId) {
        Room room = getRoom(roomId);
        return room.playerIds().contains(userId) || room.spectatorIds().contains(userId);
    }

    public List<Room> listWaitingRooms(String gameTypeFilter) {
        return repository.openRoomIds().stream()
                .map(repository::findById)
                .flatMap(Optional::stream)
                .filter(r -> gameTypeFilter == null || gameTypeFilter.equals(r.gameType()))
                .toList();
    }
}
