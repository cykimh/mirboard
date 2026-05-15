package com.mirboard.domain.lobby.room;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.infra.messaging.DomainEventBus;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository repository;
    private final GameRegistry games;
    private final Clock clock;
    private final DomainEventBus events;
    private final MirboardMetrics metrics;
    private final Random random;

    @Autowired
    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       DomainEventBus events,
                       MirboardMetrics metrics) {
        this(repository, games, clock, events, metrics, new SecureRandom());
    }

    /** Phase 8C — 테스트에서 시드 고정 Random 을 주입할 수 있도록 분리한 생성자. */
    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       DomainEventBus events,
                       MirboardMetrics metrics,
                       Random random) {
        this.repository = repository;
        this.games = games;
        this.clock = clock;
        this.events = events;
        this.metrics = metrics;
        this.random = random;
    }

    public Room createRoom(long hostUserId, String name, String gameType) {
        return createRoom(hostUserId, name, gameType, TeamPolicy.SEQUENTIAL);
    }

    public Room createRoom(long hostUserId, String name, String gameType, TeamPolicy teamPolicy) {
        GameDefinition def = games.require(gameType);
        if (def.status() != GameStatus.AVAILABLE) {
            throw new com.mirboard.domain.game.core.GameNotFoundException(gameType);
        }
        String roomId = UUID.randomUUID().toString();
        long now = Instant.now(clock).toEpochMilli();
        repository.create(roomId, hostUserId, name, gameType, def.maxPlayers(), now, teamPolicy);
        Room room = getRoom(roomId);
        events.publish(RoomChangedEvent.updated(room));
        metrics.roomCreated();
        log.info("Room created: roomId={} gameType={} hostUserId={} capacity={} teamPolicy={}",
                roomId, gameType, hostUserId, def.maxPlayers(), teamPolicy);
        return room;
    }

    /** Phase 8C — WAITING 중 호스트가 팀 정책을 변경. IN_GAME 이후엔 호출 불가. */
    public Room updateTeamPolicy(String roomId, long requesterId, TeamPolicy newPolicy) {
        Room room = getRoom(roomId);
        if (room.hostId() != requesterId) {
            throw new NotHostException(roomId);
        }
        if (room.status() != RoomStatus.WAITING) {
            throw new GameAlreadyStartedException(roomId);
        }
        repository.updateTeamPolicy(roomId, newPolicy);
        Room updated = getRoom(roomId);
        events.publish(RoomChangedEvent.updated(updated));
        log.info("Team policy updated: roomId={} requesterId={} newPolicy={}",
                roomId, requesterId, newPolicy);
        return updated;
    }

    public Room getRoom(String roomId) {
        return repository.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
    }

    public Room joinRoom(String roomId, long userId) {
        long now = Instant.now(clock).toEpochMilli();
        repository.join(roomId, userId, now);
        Room room = getRoom(roomId);
        events.publish(RoomChangedEvent.updated(room));
        metrics.roomJoined();
        log.info("Room join: roomId={} userId={} occupancy={}/{} status={}",
                roomId, userId, room.playerIds().size(), room.capacity(), room.status());
        if (room.status() == RoomStatus.IN_GAME) {
            // Phase 8C — RANDOM 정책이면 좌석 셔플 후 갱신된 좌석 순서를 브로드캐스트.
            // capacity 가 막혀 신규 join 동시성 없으므로 안전.
            if (room.teamPolicy() == TeamPolicy.RANDOM) {
                List<Long> shuffled = new ArrayList<>(room.playerIds());
                Collections.shuffle(shuffled, random);
                repository.replacePlayerOrder(roomId, shuffled);
                room = getRoom(roomId);
                events.publish(RoomChangedEvent.updated(room));
                log.info("Seats shuffled (RANDOM): roomId={} newOrder={}", roomId, shuffled);
            }
            events.publish(new com.mirboard.domain.game.core.GameStartingEvent(
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
        events.publish(remaining
                .map(RoomChangedEvent::updated)
                .orElseGet(() -> RoomChangedEvent.destroyed(roomId)));
        log.info("Room leave: roomId={} userId={} destroyed={}",
                roomId, userId, remaining.isEmpty());
    }

    public void markFinished(String roomId) {
        repository.markFinished(roomId, Instant.now(clock).toEpochMilli());
        repository.findById(roomId)
                .ifPresent(room -> events.publish(RoomChangedEvent.updated(room)));
        log.info("Room finished: roomId={}", roomId);
    }

    /**
     * Phase 8A — 직접 링크 진입 시 자동 분기. 플레이어 재접속 / 신규 입장 / 관전자
     * 추가를 한 endpoint 에서 결정. capacity 가 차 있고 본인이 원래 플레이어가
     * 아니면 자동으로 관전자로 흡수 (손패 노출 방지의 1차 방어선).
     */
    public JoinOrReconnectResult joinOrReconnect(String roomId, long userId) {
        Room room = getRoom(roomId);
        if (room.playerIds().contains(userId)) {
            log.info("Room reconnect: roomId={} userId={} status={}",
                    roomId, userId, room.status());
            return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.RECONNECTED, room);
        }
        if (room.spectatorIds().contains(userId)) {
            return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.SPECTATING, room);
        }
        if (room.status() == RoomStatus.WAITING && room.playerCount() < room.capacity()) {
            Room joined = joinRoom(roomId, userId);
            return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.JOINED, joined);
        }
        Room spectated = spectate(roomId, userId);
        return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.SPECTATING, spectated);
    }

    /**
     * Phase 8A — 호스트가 IN_GAME 방을 수동 종료. 무한 재접속 정책 하에서 끊긴
     * 플레이어가 돌아오지 않을 때 빠져나오는 유일한 경로.
     */
    public void abortGame(String roomId, long userId) {
        Room room = getRoom(roomId);
        if (room.hostId() != userId) {
            throw new NotHostException(roomId);
        }
        if (room.status() != RoomStatus.IN_GAME) {
            throw new GameNotInProgressException(roomId);
        }
        repository.markFinished(roomId, Instant.now(clock).toEpochMilli());
        repository.findById(roomId)
                .ifPresent(updated -> events.publish(RoomChangedEvent.updated(updated)));
        log.warn("Room aborted by host: roomId={} hostUserId={}", roomId, userId);
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
