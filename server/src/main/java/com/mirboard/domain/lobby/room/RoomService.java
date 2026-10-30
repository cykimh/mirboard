package com.mirboard.domain.lobby.room;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository repository;
    private final GameRegistry games;
    private final Clock clock;
    /** D-116 — 로컬 발행만. GameStartingEvent 가 다른 인스턴스에서 또 처리되면 라운드를 재딜링한다. */
    private final ApplicationEventPublisher events;
    private final MirboardMetrics metrics;
    private final Random random;
    private final BotUserRegistry bots;

    @Autowired
    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       ApplicationEventPublisher events,
                       MirboardMetrics metrics,
                       BotUserRegistry bots) {
        this(repository, games, clock, events, metrics, bots, new SecureRandom());
    }

    /** Phase 8C — 테스트에서 시드 고정 Random 을 주입할 수 있도록 분리한 생성자. */
    public RoomService(RoomRepository repository,
                       GameRegistry games,
                       Clock clock,
                       ApplicationEventPublisher events,
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

    /**
     * 인원을 받지 않는 짧은 오버로드들은 "생략"의 뜻이 둘로 갈린다 — 받지 않은 턴 제한은 {@link #DEFAULT_TURN_SECONDS}
     * (0=끔)를 <b>명시</b>해 넘기고, 인원은 null(게임 선언 {@code defaultPlayers()}, D-131)로 넘긴다. 턴 제한까지 게임 선언
     * ({@code defaultTurnSeconds()})을 따르려면 마지막 오버로드에 null 을 넘긴다(운영 호출자 {@code RoomController} 가 그렇게
     * 한다 — REST 요청에서 필드를 뺀 것이 "생략"이다). 짧은 오버로드는 테스트용이라 바꾸지 않았다(null 이면 원카드 테스트에
     * 선언값 30초 턴이 생긴다, {@code docs/plans/onecard.md} §9).
     */
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

    public Room createRoom(long hostUserId, String name, String gameType,
                           TeamPolicy teamPolicy, boolean fillWithBots, int targetScore,
                           int turnSeconds, int stake) {
        return createRoom(hostUserId, name, gameType, teamPolicy, fillWithBots, targetScore,
                turnSeconds, stake, null);
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
     * D-99 — `requestedCapacity` 방 인원. 게임이 정한 `minPlayers()..maxPlayers()` 를 벗어나면
     * InvalidCapacityException. 티츄는 min=max=4 라 4 또는 미지정만 통과한다.
     * D-131 — 생략(null)한 인원·턴 제한은 게임 선언 `def.defaultPlayers()`·`def.defaultTurnSeconds()` 다(방 만들기 모달의
     * 처음 선택과 같은 값, D-130). 티츄·스컬킹은 선언이 예전 생략 기본(최대 인원·끔)과 같아 동작이 바뀌지 않는다.
     */
    public Room createRoom(long hostUserId, String name, String gameType,
                           TeamPolicy teamPolicy, boolean fillWithBots, int targetScore,
                           Integer requestedTurnSeconds, int stake, Integer requestedCapacity) {
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
        requireSupportedRoomOptions(def, targetScore, stake);
        int capacity = requestedCapacity == null ? def.defaultPlayers() : requestedCapacity;
        int turnSeconds = requestedTurnSeconds == null ? def.defaultTurnSeconds() : requestedTurnSeconds;
        if (capacity < def.minPlayers() || capacity > def.maxPlayers()) {
            throw new InvalidCapacityException(capacity, def.minPlayers(), def.maxPlayers());
        }
        String roomId = UUID.randomUUID().toString();
        long now = Instant.now(clock).toEpochMilli();
        repository.create(roomId, hostUserId, name, gameType, capacity, now, teamPolicy,
                fillWithBots, targetScore, turnSeconds, stake);
        Room room = getRoom(roomId);
        events.publishEvent(RoomChangedEvent.updated(room));
        metrics.roomOpened(gameType);
        log.info("Room created: roomId={} gameType={} hostUserId={} capacity={} teamPolicy={} fillWithBots={} targetScore={} turnSeconds={} stake={}",
                roomId, gameType, hostUserId, capacity, teamPolicy, fillWithBots,
                targetScore, turnSeconds, stake);

        if (fillWithBots) {
            int seatsToFill = capacity - 1;  // host 1 명 이미 들어가 있음.
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
            // Phase 16(#2) — 봇은 자동 ready. all-bot 시나리오(호스트도 봇)면 마지막 봇 ready 에서 전원 ready → 자동 시작.
            for (long pid : getRoom(roomId).playerIds()) {
                if (bots.isBot(pid)) {
                    room = setReady(roomId, pid, true);
                }
            }
            // D-136 — 봇과 연습 방은 대기실이 없다. 사람 방장의 준비까지 넣어 기존 시작 길(room_ready.lua → onGameStart)로 곧바로 시작.
            if (room.status() == RoomStatus.WAITING) {
                room = setReady(roomId, hostUserId, true);
            }
        }
        return room;
    }

    /**
     * D-106 — 게임이 쓰지 않는 방 설정에 <b>기본값 아닌 값</b>이 오면 거절한다.
     *
     * <p>기본값은 통과시킨다. 클라가 늘 `targetScore:1000` 을 보내던 시절의 호출부와
     * 기존 테스트를 깨지 않으면서, "의도적으로 설정한 값"만 잡기 위해서다. 예컨대 스컬킹
     * 방에 stake=100 이 오면 여기서 막힌다 — 예전엔 통과한 뒤 `RoomChipService` 가 조용히
     * return 해서 칩 없이 봇만 금지된 방이 만들어졌다.
     *
     * <p><b>`teamPolicy` 는 검사하지 않는다</b>(D-106 정정). 좌석 정책은 게임 중립이다 —
     * `domain.game` 에 `TeamPolicy` 참조가 0건이고, `RANDOM` 은 {@link #onGameStart} 에서
     * 좌석 순서를 섞을 뿐이다. 개인전에서도 좌석 순서 = 턴 순서라 의미가 있으므로,
     * `TEAMS` 는 UI 라벨("팀 배정" vs "좌석 순서")만 가른다.
     */
    private static void requireSupportedRoomOptions(GameDefinition def, int targetScore,
                                                    int stake) {
        var supported = def.supportedRoomOptions();
        if (targetScore != DEFAULT_TARGET_SCORE && !supported.contains(RoomOption.TARGET_SCORE)) {
            throw new UnsupportedRoomOptionException(def.id(), RoomOption.TARGET_SCORE);
        }
        if (stake != DEFAULT_STAKE && !supported.contains(RoomOption.BETTING)) {
            throw new UnsupportedRoomOptionException(def.id(), RoomOption.BETTING);
        }
    }

    /**
     * Phase 8C — WAITING 중 호스트가 좌석 정책을 변경. IN_GAME 이후엔 호출 불가.
     *
     * <p>이름은 `teamPolicy` 지만 실제로는 <b>좌석 순서 정책</b>이라 게임을 가리지 않는다
     * (D-106 정정) — 개인전에서도 좌석 순서 = 턴 순서이므로 셔플이 의미 있다. 팀 유무는
     * 클라의 라벨("팀 배정" vs "좌석 순서")만 바꾼다.
     */
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
        events.publishEvent(RoomChangedEvent.updated(updated));
        log.info("Team policy updated: roomId={} requesterId={} newPolicy={}",
                roomId, requesterId, newPolicy);
        return updated;
    }

    /** D-161 — 대기실 설정 범위(턴 제한 초·목표 점수). 화면 선택지는 게임이 선언하고, 서버는 범위만 본다(D-154 와 같다). */
    static final int MAX_TURN_SECONDS = 600;
    static final int MIN_TARGET_SCORE = 100;
    static final int MAX_TARGET_SCORE = 10_000;

    /**
     * D-161 — 대기실에서 방장이 게임 설정을 바꾼다(부분 갱신 — null 은 그대로). 검증은 만들 때와 같다: 게임이 선언한 방 설정만
     * (D-106), 판돈 허용값·봇 방 판돈 금지. 시작 여부 확인과 쓰기는 {@code room_update_settings.lua} 한 번이라 확인 뒤 시작된
     * 방의 설정은 바뀌지 않는다. 다른 사람 화면은 메타 갱신({@link RoomChangedEvent})으로 바로 바뀐다.
     */
    public Room updateSettings(String roomId, long requesterId, RoomSettingsUpdate update) {
        Room room = getRoom(roomId);
        if (room.hostId() != requesterId) {
            throw new NotHostException(roomId);
        }
        if (room.status() != RoomStatus.WAITING) {
            throw new GameAlreadyStartedException(roomId);
        }
        GameDefinition def = games.require(room.gameType());
        var supported = def.supportedRoomOptions();
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        if (update.turnSeconds() != null) {
            int v = update.turnSeconds();
            if (v < 0 || v > MAX_TURN_SECONDS) {
                throw new InvalidRoomSettingException("turnSeconds", v);
            }
            fields.put("turnSeconds", Integer.toString(v));
        }
        if (update.targetScore() != null) {
            int v = update.targetScore();
            if (!supported.contains(RoomOption.TARGET_SCORE)) {
                throw new UnsupportedRoomOptionException(def.id(), RoomOption.TARGET_SCORE);
            }
            if (v < MIN_TARGET_SCORE || v > MAX_TARGET_SCORE) {
                throw new InvalidRoomSettingException("targetScore", v);
            }
            fields.put("targetScore", Integer.toString(v));
        }
        if (update.stake() != null) {
            int v = update.stake();
            if (!supported.contains(RoomOption.BETTING)) {
                throw new UnsupportedRoomOptionException(def.id(), RoomOption.BETTING);
            }
            if (!ALLOWED_STAKES.contains(v)) {
                throw new InvalidStakeException(v);
            }
            if (v > 0 && room.fillWithBots()) {
                throw new StakedRoomNoBotsException();
            }
            fields.put("stake", Integer.toString(v));
        }
        if (update.raceButtonSize() != null) {
            String v = update.raceButtonSize();
            if (!supported.contains(RoomOption.RACE_BUTTON_SIZE)) {
                throw new UnsupportedRoomOptionException(def.id(), RoomOption.RACE_BUTTON_SIZE);
            }
            if (!Room.RACE_BUTTON_SIZES.contains(v)) {
                throw new InvalidRoomSettingException("raceButtonSize", v);
            }
            fields.put("raceButtonSize", v);
        }
        if (update.raceButtonVisibility() != null) {
            String v = update.raceButtonVisibility();
            if (!supported.contains(RoomOption.RACE_BUTTON_VISIBILITY)) {
                throw new UnsupportedRoomOptionException(def.id(), RoomOption.RACE_BUTTON_VISIBILITY);
            }
            if (!Room.RACE_BUTTON_VISIBILITIES.contains(v)) {
                throw new InvalidRoomSettingException("raceButtonVisibility", v);
            }
            fields.put("raceButtonVisibility", v);
        }
        if (fields.isEmpty()) {
            return room;
        }
        switch (repository.updateSettings(roomId, fields)) {
            case 1 -> { }
            case -1 -> throw new RoomNotFoundException(roomId);
            case -2 -> throw new GameAlreadyStartedException(roomId);
            default -> throw new IllegalStateException("room_update_settings.lua returned unexpected code");
        }
        Room updated = getRoom(roomId);
        events.publishEvent(RoomChangedEvent.updated(updated));
        log.info("Room settings updated: roomId={} host={} fields={}", roomId, requesterId, fields);
        return updated;
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
        events.publishEvent(RoomChangedEvent.updated(room));
        log.info("Room ready toggle: roomId={} userId={} ready={} started={}",
                roomId, userId, ready, code == 1);
        if (code == 1) {
            room = onGameStart(room);
        }
        return room;
    }

    /**
     * D-136·D-143 — 친구와 하기 대기실의 방장 시작. 방장만, 게임 최소 인원 이상이면 정원을 현재 인원으로 줄이고 시작한다(room_start_now.lua
     * 한 번 — 준비는 조건이 아니고 정원이 다 차도 이 길로 시작한다). 시작 뒤는 전원 준비로 시작할 때와 같은 절차({@link #onGameStart}).
     */
    public Room startNow(String roomId, long hostUserId) {
        Room room = getRoom(roomId);
        if (room.hostId() != hostUserId) {
            throw new NotHostException(roomId);
        }
        int minPlayers = games.require(room.gameType()).minPlayers();
        int code = repository.startNow(roomId, hostUserId, minPlayers);
        switch (code) {
            case 1 -> { }
            case -1 -> throw new RoomNotFoundException(roomId);
            case -2 -> throw new GameAlreadyStartedException(roomId);
            case -3 -> throw new NotInRoomException(roomId);
            case -4 -> throw new NotEnoughPlayersException(roomId, minPlayers);
            default -> throw new IllegalStateException("room_start_now.lua returned unexpected: " + code);
        }
        Room started = getRoom(roomId);
        events.publishEvent(RoomChangedEvent.updated(started));
        log.info("Room started now: roomId={} host={} players={}", roomId, hostUserId, started.playerIds());
        return onGameStart(started);
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
            events.publishEvent(RoomChangedEvent.updated(room));
            log.info("Seats shuffled (RANDOM): roomId={} newOrder={}", roomId, shuffled);
        }
        events.publishEvent(new com.mirboard.domain.game.core.GameStartingEvent(
                room.roomId(), room.gameType(), room.playerIds(), room.targetScore()));
        metrics.gameStarted(room.gameType());
        log.info("Game starting: roomId={} gameType={} players={}",
                roomId, room.gameType(), room.playerIds());
        return room;
    }

    public void leaveRoom(String roomId, long userId) {
        repository.leave(roomId, userId); // BY-3 — 준비 플래그도 스크립트가 같은 원자 구간에서 지운다.
        Optional<Room> remaining = repository.findById(roomId);
        events.publishEvent(remaining
                .map(RoomChangedEvent::updated)
                .orElseGet(() -> RoomChangedEvent.destroyed(roomId)));
        log.info("Room leave: roomId={} userId={} destroyed={}",
                roomId, userId, remaining.isEmpty());
    }

    /**
     * D-153 — 정상 종료한 친구 방을 대기실로 돌린다(정원 = 게임 최대 인원, 관전자 자동 착석, 방 목록 재등록). 진행 중이 아니면 그대로
     * 돌려준다. 방장이 다시 시작하면({@link #startNow}) 다음 판이다.
     */
    public Room returnToWaiting(String roomId) {
        Room room = getRoom(roomId);
        int max = games.require(room.gameType()).maxPlayers();
        int seated = repository.returnToWaiting(roomId, max, Instant.now(clock).toEpochMilli());
        if (seated == -1) {
            throw new RoomNotFoundException(roomId);
        }
        Room after = getRoom(roomId);
        if (seated >= 0) {
            events.publishEvent(RoomChangedEvent.updated(after));
            log.info("Room back to waiting: roomId={} players={} spectators={}",
                    roomId, after.playerIds(), after.spectatorIds());
        }
        return after;
    }

    public void markFinished(String roomId) {
        repository.markFinished(roomId, Instant.now(clock).toEpochMilli());
        repository.findById(roomId)
                .ifPresent(room -> events.publishEvent(RoomChangedEvent.updated(room)));
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
            try {
                Room joined = joinRoom(roomId, userId);
                return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.JOINED, joined);
            } catch (AlreadyInRoomException race) {
                // D-133 — 같은 사람의 동시 이중 요청(두 탭·개발 StrictMode 의 이중 effect)이 둘 다 "플레이어 아님"을 읽고
                // 입장으로 왔다. 먼저 간 요청이 앉혔으면 이 요청은 재접속이다(D-74 멱등). 경합 ROOM_FULL·
                // GAME_ALREADY_STARTED 는 잡지 않는다 — 클라 방 화면이 그 코드로 "관전할까요?" 패널을 띄운다(결정 A).
                Room seated = getRoom(roomId);
                if (!seated.playerIds().contains(userId)) {
                    throw race;
                }
                log.info("Room reconnect (concurrent join): roomId={} userId={}", roomId, userId);
                return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.RECONNECTED, seated);
            }
        }
        Room spectated = spectate(roomId, userId);
        return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.SPECTATING, spectated);
    }

    /**
     * D-122 — 호스트 강제 종료의 사전 검증(방 액션 락을 잡기 전). 호스트가 아니거나 진행 중이
     * 아니면 던진다. 락 안의 {@link #abortGame} 이 같은 검사를 다시 한다(그 사이 상태가 바뀔 수
     * 있다) — 이 메서드는 남의 방 락을 쥐는 요청을 막는 문지기일 뿐이다.
     */
    public void checkHostAbort(String roomId, long userId) {
        Room room = getRoom(roomId);
        if (room.hostId() != userId) {
            throw new NotHostException(roomId);
        }
        if (room.status() != RoomStatus.IN_GAME) {
            throw new GameNotInProgressException(roomId);
        }
        // D-135 — 방장 말고 모두 봇인 판만. 강제 종료는 매치 기록·레이팅을 남기지 않아 사람 판에서는 판 깨기가 된다
        // (사람 판의 끊김은 탈주 유예, 멈춘 판은 운영자 강제 종료 — adminAbortGame 은 이 검사를 거치지 않는다).
        List<Long> players = room.playerIds();
        for (int seat = 0; seat < players.size(); seat++) {
            if (players.get(seat) != userId && !room.botSeats().contains(seat)) {
                throw new HumanPlayersPresentException(roomId);
            }
        }
    }

    /**
     * Phase 8A — 호스트가 IN_GAME 방을 수동 종료. 무한 재접속 정책 하에서 끊긴
     * 플레이어가 돌아오지 않을 때 빠져나오는 유일한 경로.
     */
    public void abortGame(String roomId, long userId) {
        checkHostAbort(roomId, userId);
        repository.markFinished(roomId, Instant.now(clock).toEpochMilli());
        repository.findById(roomId)
                .ifPresent(updated -> events.publishEvent(RoomChangedEvent.updated(updated)));
        log.warn("Room aborted by host: roomId={} hostUserId={}", roomId, userId);
    }

    /**
     * D-86 — 어드민이 진행 중 매치를 강제 종료. {@link #abortGame} 과 달리 host 검증이 없다.
     * 권한 검사는 호출 측(AdminController → AdminAuthorization)이 수행한다(규칙#4).
     */
    public void adminAbortGame(String roomId) {
        Room room = getRoom(roomId);
        if (room.status() != RoomStatus.IN_GAME) {
            throw new GameNotInProgressException(roomId);
        }
        repository.markFinished(roomId, Instant.now(clock).toEpochMilli());
        repository.findById(roomId)
                .ifPresent(updated -> events.publishEvent(RoomChangedEvent.updated(updated)));
        log.warn("Room aborted by admin: roomId={}", roomId);
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
                events.publishEvent(RoomChangedEvent.destroyed(roomId));
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
