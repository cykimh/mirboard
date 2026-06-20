package com.mirboard.domain.lobby.room;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
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
    private final BotUserRegistry bots;

    @Autowired
    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       DomainEventBus events,
                       MirboardMetrics metrics,
                       BotUserRegistry bots) {
        this(repository, games, clock, events, metrics, bots, new SecureRandom());
    }

    /** Phase 8C — 테스트에서 시드 고정 Random 을 주입할 수 있도록 분리한 생성자. */
    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       DomainEventBus events,
                       MirboardMetrics metrics,
                       BotUserRegistry bots,
                       Random random) {
        this.repository = repository;
        this.games = games;
        this.clock = clock;
        this.events = events;
        this.metrics = metrics;
        this.bots = bots;
        this.random = random;
    }

    /** Phase 12 — 기본 목표점수 (방 생성 시 미지정 시). */
    public static final int DEFAULT_TARGET_SCORE = 1000;
    /** Phase 13D — 기본 턴 제한 (0=끔). */
    public static final int DEFAULT_TURN_SECONDS = 0;
    /** D-81 — 기본 판돈 (0=내기 없음). */
    public static final int DEFAULT_STAKE = 0;
    /** D-81 — 허용 판돈(가상 칩). 0=내기 없음. 임의값/음수 차단. */
    public static final java.util.Set<Integer> ALLOWED_STAKES =
            java.util.Set.of(0, 10, 50, 100, 500);

    public Room createRoom(long hostUserId, String name, String gameType) {
        return createRoom(hostUserId, name, gameType, TeamPolicy.SEQUENTIAL, false,
                DEFAULT_TARGET_SCORE, DEFAULT_TURN_SECONDS);
    }

    public Room createRoom(long hostUserId, String name, String gameType, TeamPolicy teamPolicy) {
        return createRoom(hostUserId, name, gameType, teamPolicy, false, DEFAULT_TARGET_SCORE,
                DEFAULT_TURN_SECONDS);
    }

    public Room createRoom(long hostUserId, String name, String gameType,
                           TeamPolicy teamPolicy, boolean fillWithBots) {
        return createRoom(hostUserId, name, gameType, teamPolicy, fillWithBots,
                DEFAULT_TARGET_SCORE, DEFAULT_TURN_SECONDS);
    }

    public Room createRoom(long hostUserId, String name, String gameType,
                           TeamPolicy teamPolicy, boolean fillWithBots, int targetScore) {
        return createRoom(hostUserId, name, gameType, teamPolicy, fillWithBots, targetScore,
                DEFAULT_TURN_SECONDS);
    }

    public Room createRoom(long hostUserId, String name, String gameType,
                           TeamPolicy teamPolicy, boolean fillWithBots, int targetScore,
                           int turnSeconds) {
        return createRoom(hostUserId, name, gameType, teamPolicy, fillWithBots, targetScore,
                turnSeconds, DEFAULT_STAKE);
    }

    /**
     * Phase 9B — `fillWithBots=true` 면 createRoom 직후 capacity 가 찰 때까지 시드 봇을
     * 자동 join. capacity 도달 시 일반 joinRoom 흐름과 동일하게 IN_GAME 전이 +
     * GameStartingEvent 발행.
     *
     * Phase 12 — `targetScore` 매치 종료 목표점수 (기본 1000).
     * Phase 13D — `turnSeconds` 개인 턴 제한 (0=끔). 타이머는 매치 상태가 아니라
     * 방 메타라 TichuMatchState 까진 흘리지 않고 스케줄러가 room 으로 참조.
     * D-81 — `stake` 판돈(가상 칩, 0=내기 없음). 허용값 외/음수는 거절하고,
     * stake>0 이면 봇 채우기 금지(봇=무한 잔액 → 칩 파밍 방지).
     */
    public Room createRoom(long hostUserId, String name, String gameType,
                           TeamPolicy teamPolicy, boolean fillWithBots, int targetScore,
                           int turnSeconds, int stake) {
        GameDefinition def = games.require(gameType);
        if (def.status() != GameStatus.AVAILABLE) {
            throw new com.mirboard.domain.game.core.GameNotFoundException(gameType);
        }
        if (!ALLOWED_STAKES.contains(stake)) {
            throw new InvalidStakeException(stake);
        }
        if (stake > 0 && fillWithBots) {
            throw new StakedRoomNoBotsException();
        }
        String roomId = UUID.randomUUID().toString();
        long now = Instant.now(clock).toEpochMilli();
        repository.create(roomId, hostUserId, name, gameType, def.maxPlayers(), now, teamPolicy,
                fillWithBots, targetScore, turnSeconds, stake);
        Room room = getRoom(roomId);
        events.publish(RoomChangedEvent.updated(room));
        metrics.roomCreated();
        log.info("Room created: roomId={} gameType={} hostUserId={} capacity={} teamPolicy={} fillWithBots={} targetScore={} turnSeconds={} stake={}",
                roomId, gameType, hostUserId, def.maxPlayers(), teamPolicy, fillWithBots,
                targetScore, turnSeconds, stake);

        if (fillWithBots) {
            int seatsToFill = def.maxPlayers() - 1;  // host 1 명 이미 들어가 있음.
            // 호스트가 봇일 수 있으므로 (테스트용 all-bot 시나리오) — 호스트와 다른 봇만 선택.
            List<Long> botIds = bots.getBotIds().stream()
                    .filter(id -> id != hostUserId)
                    .limit(seatsToFill)
                    .toList();
            if (botIds.size() < seatsToFill) {
                throw new IllegalStateException(
                        "Not enough distinct bots to fill " + seatsToFill + " seats");
            }
            log.info("Auto-joining bots: roomId={} botIds={}", roomId, botIds);
            for (long botId : botIds) {
                room = joinRoom(roomId, botId);
            }
            // Phase 16(#2) — 봇은 자동 ready. 솔로(사람 호스트)면 호스트가
            // RoomPage 에서 준비 눌러야 시작. all-bot 시나리오(호스트도 봇)면
            // 마지막 봇 ready 에서 전원 ready → 자동 시작(기존 동작 보존).
            for (long pid : getRoom(roomId).playerIds()) {
                if (bots.isBot(pid)) {
                    room = setReady(roomId, pid, true);
                }
            }
        }
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
        // Phase 16(#2) — join 은 더 이상 게임을 시작하지 않음. 시작은 setReady.
        return room;
    }

    /**
     * Phase 16(#2) — 대기실 준비 토글. 전원 ready 가 되면 (room_ready.lua 가
     * WAITING→IN_GAME 원자 전이) 게임 시작 절차를 수행한다. 멤버가 아니면
     * NotInRoomException, 이미 시작/종료된 방이면 GameAlreadyStartedException.
     */
    public Room setReady(String roomId, long userId, boolean ready) {
        getRoom(roomId); // 존재 확인 (없으면 RoomNotFound).
        // D-82 — 방 단위 테이블 칩: ready 전 계정 잔액 검증 없음(게임 시작 시 전원 동일 칩).
        int code = repository.setReady(roomId, userId, ready);
        Room room = getRoom(roomId);
        events.publish(RoomChangedEvent.updated(room));
        log.info("Room ready toggle: roomId={} userId={} ready={} started={}",
                roomId, userId, ready, code == 1);
        if (code == 1) {
            room = onGameStart(room);
        }
        return room;
    }

    /**
     * Phase 8C/16 — WAITING→IN_GAME 전이 직후 절차: RANDOM 정책이면 좌석 셔플,
     * GameStartingEvent 발행, metrics. (예전 joinRoom 의 capacity 자동시작
     * 분기에서 추출. 이제 전원 ready 시점에 한 번만 실행.)
     */
    private Room onGameStart(Room room) {
        String roomId = room.roomId();
        if (room.teamPolicy() == TeamPolicy.RANDOM) {
            List<Long> shuffled = new ArrayList<>(room.playerIds());
            Collections.shuffle(shuffled, random);
            repository.replacePlayerOrder(roomId, shuffled);
            room = getRoom(roomId);
            events.publish(RoomChangedEvent.updated(room));
            log.info("Seats shuffled (RANDOM): roomId={} newOrder={}", roomId, shuffled);
        }
        events.publish(new com.mirboard.domain.game.core.GameStartingEvent(
                room.roomId(), room.gameType(), room.playerIds(), room.targetScore()));
        metrics.gameStarted();
        log.info("Game starting: roomId={} gameType={} players={}",
                roomId, room.gameType(), room.playerIds());
        return room;
    }

    public void leaveRoom(String roomId, long userId) {
        repository.leave(roomId, userId);
        repository.clearReady(roomId, userId); // Phase 16(#2) — ready 플래그 정리.
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
        destroyIfEmpty(roomId);
    }

    /**
     * Phase 19(#1, D-75) — 플레이어 0 && 관전자 0 이면 방을 즉시 소멸시킨다.
     * 관전자만 남았다가 마지막 관전자가 나간 방을 정리. 플레이어 leave 의 빈 방
     * 소멸은 room_leave.lua 가 이미 처리하므로 여기서는 관전자-only 케이스 담당.
     */
    private void destroyIfEmpty(String roomId) {
        repository.findById(roomId).ifPresent(room -> {
            if (room.playerIds().isEmpty() && room.spectatorIds().isEmpty()
                    && repository.deleteRoom(roomId)) {
                events.publish(RoomChangedEvent.destroyed(roomId));
                log.info("Room destroyed (empty — no players/spectators): roomId={}", roomId);
            }
        });
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
