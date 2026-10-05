package com.mirboard.infra.rest.rooms;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.JoinOrReconnectResult;
import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.ResyncNotAvailableException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameAbortService;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.infra.ws.RoomPresence;
import com.mirboard.infra.ws.RoomSeq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomService rooms;
    private final GameEngineProvider engines;
    private final RoomSeq seqs;
    private final DesertionService desertion;
    private final RoomPresence sessions;
    private final RoomChipStore chipStore;
    private final GameAbortService aborts;
    private final RoomActionLock lock;

    public RoomController(RoomService rooms,
                          GameEngineProvider engines,
                          RoomSeq seqs,
                          DesertionService desertion,
                          RoomPresence sessions,
                          RoomChipStore chipStore,
                          GameAbortService aborts,
                          RoomActionLock lock) {
        this.rooms = rooms;
        this.engines = engines;
        this.seqs = seqs;
        this.desertion = desertion;
        this.sessions = sessions;
        this.chipStore = chipStore;
        this.aborts = aborts;
        this.lock = lock;
    }

    @GetMapping
    public ListResponse list(@RequestParam(required = false) String gameType,
                             @RequestParam(required = false, defaultValue = "WAITING") RoomStatus status) {
        List<Room> result = status == RoomStatus.WAITING
                ? rooms.listWaitingRooms(gameType)
                : List.of();
        return new ListResponse(result);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Room create(@AuthenticationPrincipal AuthPrincipal me,
                       @RequestBody @Valid CreateRequest req) {
        TeamPolicy policy = req.teamPolicy() == null ? TeamPolicy.SEQUENTIAL : req.teamPolicy();
        boolean fillWithBots = Boolean.TRUE.equals(req.fillWithBots());
        int targetScore = req.targetScore() == null
                ? com.mirboard.domain.lobby.room.RoomService.DEFAULT_TARGET_SCORE
                : req.targetScore();
        int turnSeconds = req.turnSeconds() == null
                ? com.mirboard.domain.lobby.room.RoomService.DEFAULT_TURN_SECONDS
                : req.turnSeconds();
        int stake = req.stake() == null
                ? com.mirboard.domain.lobby.room.RoomService.DEFAULT_STAKE
                : req.stake();
        // D-99 — capacity 는 선택. null 이면 RoomService 가 def.maxPlayers() 로 채운다.
        return rooms.createRoom(me.userId(), req.name(), req.gameType(), policy,
                fillWithBots, targetScore, turnSeconds, stake, req.capacity());
    }

    /** Phase 8C — WAITING 방에서 호스트가 팀 정책 변경. */
    @org.springframework.web.bind.annotation.PutMapping("/{roomId}/team-policy")
    public Room updateTeamPolicy(@PathVariable String roomId,
                                 @AuthenticationPrincipal AuthPrincipal me,
                                 @RequestBody @Valid UpdateTeamPolicyRequest req) {
        return rooms.updateTeamPolicy(roomId, me.userId(), req.teamPolicy());
    }

    @GetMapping("/{roomId}")
    public Room get(@PathVariable String roomId) {
        return rooms.getRoom(roomId);
    }

    /**
     * Phase 16(#2) — 대기실 준비 토글. 전원(봇 자동 포함) 준비되면 서버가
     * WAITING→IN_GAME 전이 + 게임 시작. 응답은 갱신된 Room(readyUserIds 포함).
     */
    @PostMapping("/{roomId}/ready")
    public Room ready(@PathVariable String roomId,
                      @AuthenticationPrincipal AuthPrincipal me,
                      @RequestBody @Valid ReadyRequest req) {
        return rooms.setReady(roomId, me.userId(), req.ready());
    }

    @PostMapping("/{roomId}/join")
    public Room join(@PathVariable String roomId,
                     @AuthenticationPrincipal AuthPrincipal me) {
        return rooms.joinRoom(roomId, me.userId());
    }

    /**
     * D-82 — 호스트가 매치 종료 후 같은 테이블에서 '한 판 더'(리매치). 칩은 누적되고
     * 판돈 미만 보유자는 새 매치 시작 시 무료 재바이인된다. 매치가 끝난 상태에서만 허용.
     */
    @PostMapping("/{roomId}/rematch")
    public Room rematch(@PathVariable String roomId,
                        @AuthenticationPrincipal AuthPrincipal me) {
        // 매치가 실제로 끝났는지는 게임이 판정한다 (티츄=목표점수 도달, D-98).
        if (!engines.forRoom(rooms.getRoom(roomId)).isMatchOver()) {
            throw new com.mirboard.domain.lobby.room.GameNotInProgressException(roomId);
        }
        return rooms.rematch(roomId, me.userId());
    }

    /**
     * Phase 8A — 직접 링크로 들어오는 사용자를 자동으로 분기. 본인이 원래 플레이어면
     * RECONNECTED, 빈 자리면 JOINED, IN_GAME 방에 처음 들어왔으면 SPECTATING.
     */
    @PostMapping("/{roomId}/join-or-reconnect")
    public JoinOrReconnectResponse joinOrReconnect(@PathVariable String roomId,
                                                   @AuthenticationPrincipal AuthPrincipal me) {
        JoinOrReconnectResult result = rooms.joinOrReconnect(roomId, me.userId());
        return new JoinOrReconnectResponse(result.mode().name(), result.room());
    }

    /** Phase 8A — 호스트만, IN_GAME 일 때만 가능. */
    @PostMapping("/{roomId}/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@PathVariable String roomId,
                      @AuthenticationPrincipal AuthPrincipal me) {
        // D-122 — 방 액션 락 안에서 FINISHED 전이 + 턴 데드라인 취소(진행 중 액션과 직렬화).
        aborts.abortByHost(roomId, me.userId());
    }

    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String roomId,
                      @AuthenticationPrincipal AuthPrincipal me) {
        // Phase 19(#3, D-75) — IN_GAME 중 플레이어의 명시적 '나가기' 는 탈주.
        // DesertionService 가 상대팀 승리로 매치를 종료하고 패널티를 기록한다.
        try {
            Room room = rooms.getRoom(roomId);
            if (room.status() == RoomStatus.IN_GAME
                    && room.playerIds().contains(me.userId())) {
                if (desertion.processDesertion(roomId, me.userId())) {
                    return;
                }
                // D-122 — 탈주가 처리되지 않았다고 일반 leave 로 넘기면 IN_GAME 의 LREM 이
                // 라이브 매치의 좌석을 당긴다(좌석 판정·비공개 이벤트 라우팅이 남의 좌석으로).
                if (!seatMayBeReleased(roomId)) {
                    return;
                }
            }
        } catch (RoomNotFoundException ignored) {
            // 이미 소멸 — 아래 leaveRoom 이 RoomNotFound 를 동일 처리.
        }
        rooms.leaveRoom(roomId, me.userId());
    }

    /**
     * D-122 — IN_GAME 참가자의 탈주가 처리되지 않은 뒤 일반 leave 로 넘겨도 되는가.
     *
     * <p>처리되지 않는 경우는 셋이다: 이미 탈주한 좌석의 재요청(더블클릭, 유예 탈주 뒤 재접속해
     * '나가기' — 스컬킹처럼 남은 사람끼리 계속하는 게임), 락 획득 실패, 매치가 이미 끝남. 앞의
     * 둘은 <b>매치가 진행 중</b>이라 좌석을 그대로 둔다(아무것도 안 함 — 끊김은 유예 탈주가
     * 처리한다). 넘기는 것은 매치가 끝나 리매치를 기다리는 방(티츄 사람만, D-82 — 좌석이 당겨지는
     * 그 경로는 별건)과, 그 사이 방이 IN_GAME 을 벗어난 경우(FINISHED 는 lua 가 좌석을 고정)뿐이다.
     * 판정은 게임 중립 — 매치 종료 여부는 엔진 포트가 답한다.
     */
    private boolean seatMayBeReleased(String roomId) {
        Room now = rooms.getRoom(roomId);
        if (now.status() != RoomStatus.IN_GAME) {
            return true;
        }
        return engines.forRoom(now).isMatchOver();
    }

    /** 관전 시작. 플레이어로 입장한 방은 거절. */
    @PostMapping("/{roomId}/spectate")
    public Room spectate(@PathVariable String roomId,
                         @AuthenticationPrincipal AuthPrincipal me) {
        return rooms.spectate(roomId, me.userId());
    }

    /** 관전 종료. 등록 안 되어 있어도 204. */
    @DeleteMapping("/{roomId}/spectate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopSpectating(@PathVariable String roomId,
                               @AuthenticationPrincipal AuthPrincipal me) {
        rooms.stopSpectating(roomId, me.userId());
    }

    @GetMapping("/{roomId}/resync")
    public ResyncResponse resync(@PathVariable String roomId,
                                 @AuthenticationPrincipal AuthPrincipal me) {
        Room room = rooms.getRoom(roomId);
        int seat = room.playerIds().indexOf(me.userId());
        boolean isSpectator = room.spectatorIds().contains(me.userId());
        if (seat < 0 && !isSpectator) {
            throw new NotInRoomException(roomId);
        }
        GameEngine engine = engines.forRoom(room);
        int privateSeat = occupiedSeat(room, seat);
        // D-126 — 상태·순번·뷰는 방 액션 락 안에서 같은 시점으로 읽는다. 액션은 이 락 안에서
        // 저장 → 브로드캐스트(순번 발급)를 끝내므로, 따로 읽으면 그 사이 액션이 끼어 스냅샷과
        // eventSeq 가 어긋난다(클라가 이벤트를 두 번 적용하거나 놓친다). 못 잡으면(약 3초) 예전처럼
        // 잠금 없이 읽는다 — resync 가 실패하는 것보다 낫다.
        boolean locked = lock.acquireWaiting(roomId);
        if (!locked) {
            log.warn("Resync without room lock (busy): roomId={}", roomId);
        }
        Snapshot snap;
        try {
            GameState state = engine.loadState()
                    .orElseThrow(() -> new ResyncNotAvailableException(roomId));
            snap = new Snapshot(
                    engine.phaseName(state),
                    seqs.current(roomId),
                    engine.publicView(state),
                    // 관전자는 손패 없음 — 공개 뷰만 받음. 비공개 상태가 없는 게임도 null.
                    privateSeat >= 0 ? engine.privateView(state, privateSeat).orElse(null) : null);
        } finally {
            if (locked) {
                lock.release(roomId);
            }
        }
        return new ResyncResponse(
                roomId,
                snap.phase(),
                snap.eventSeq(),
                snap.tableView(),
                snap.privateHand(),
                disconnectedSeats(room, me.userId()),
                chipStore.stacks(roomId)); // D-82 — 방 칩 스택(입장/재접속 시 즉시 표시).
    }

    /** 락 안에서 함께 읽어야 하는 resync 부분 — 게임 상태에서 나온 것과 그 시점의 순번. */
    private record Snapshot(String phase, long eventSeq, Object tableView, Object privateHand) {
    }

    /**
     * D-122 심층 방어(State Hiding) — 비공개 뷰는 요청자가 <b>실제로 앉은 좌석</b>에만 준다.
     *
     * <p>좌석 번호는 게임이 시작될 때 정원({@code capacity})만큼 찬 좌석 목록의 인덱스다.
     * 시작 뒤 목록이 줄었다면(leave 폴백의 {@code LREM}) 뒤쪽 사람들의 {@code indexOf} 가 한
     * 칸씩 당겨져 <b>남의 좌석</b>을 가리킨다 — 그대로 쓰면 다른 좌석의 손패·미공개 예측이
     * 나간다. 그래서 목록이 정원과 다르면 좌석을 확신할 수 없다고 보고 관전자 뷰(공개만)로
     * 떨어뜨린다. FINISHED 방은 좌석을 고정하므로(room_leave.lua) 정상 경로에서는 걸리지 않는다.
     *
     * @return 비공개 뷰를 줄 좌석, 줄 수 없으면 -1
     */
    private static int occupiedSeat(Room room, int indexInList) {
        if (indexInList < 0 || room.playerIds().size() != room.capacity()) {
            return -1;
        }
        return indexInList;
    }

    /**
     * 현재 끊겨 있는 플레이어 좌석 — resync 시 새 클라가 즉시 반영하도록. 라이브 세션이
     * 없는 좌석을 끊김으로 본다. 봇 좌석(세션 없음)과 요청자 본인(지금 연결됨)은 제외.
     */
    private List<Integer> disconnectedSeats(Room room, long requesterId) {
        List<Integer> result = new ArrayList<>();
        List<Long> playerIds = room.playerIds();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long pid = playerIds.get(seat);
            if (pid == requesterId || room.botSeats().contains(seat)) {
                continue;
            }
            if (!sessions.hasLiveSession(pid, room.roomId())) {
                result.add(seat);
            }
        }
        return result;
    }

    public record CreateRequest(@NotBlank String name,
                                @NotBlank String gameType,
                                TeamPolicy teamPolicy,
                                Boolean fillWithBots,
                                Integer targetScore,
                                Integer turnSeconds,
                                Integer stake,
                                // D-99 — 방 인원. null 이면 GameDefinition.maxPlayers().
                                Integer capacity) {
    }

    public record UpdateTeamPolicyRequest(@jakarta.validation.constraints.NotNull TeamPolicy teamPolicy) {
    }

    public record ReadyRequest(@jakarta.validation.constraints.NotNull Boolean ready) {
    }

    public record ListResponse(List<Room> rooms) {
    }

    public record JoinOrReconnectResponse(String mode, Room room) {
    }

    /**
     * D-98 — `tableView`/`privateHand` 는 게임별 뷰 타입이라 {@code Object} 다. Jackson 이
     * 런타임 타입으로 직렬화하므로 응답 JSON 은 종전과 동일하다 (티츄: TableView/PrivateHand).
     */
    public record ResyncResponse(
            String roomId,
            String phase,
            long eventSeq,
            Object tableView,
            Object privateHand,
            List<Integer> disconnectedSeats,
            // D-82 — 방 단위 테이블 칩 스택(userId→칩). 내기 없는 방은 빈 맵.
            java.util.Map<Long, Long> chips) {
    }
}
