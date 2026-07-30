package com.mirboard.infra.rest.rooms;

import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.PrivateHand;
import com.mirboard.domain.game.tichu.state.TableView;
import com.mirboard.domain.game.tichu.state.TichuStateMapper;
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
import com.mirboard.infra.ws.RoomPresence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
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

    private final RoomService rooms;
    private final TichuGameStateStore stateStore;
    private final TichuMatchStateStore matchStateStore;
    private final DesertionService desertion;
    private final RoomPresence sessions;
    private final RoomChipStore chipStore;

    public RoomController(RoomService rooms,
                          TichuGameStateStore stateStore,
                          TichuMatchStateStore matchStateStore,
                          DesertionService desertion,
                          RoomPresence sessions,
                          RoomChipStore chipStore) {
        this.rooms = rooms;
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
        this.desertion = desertion;
        this.sessions = sessions;
        this.chipStore = chipStore;
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
        TichuMatchState ms = matchStateStore.load(roomId).orElse(null);
        if (ms == null || !ms.isMatchOver()) {
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
        rooms.abortGame(roomId, me.userId());
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
                // D-82 — 매치 종료 후(리매치 대기) 등 탈주 미해당이면 false → 일반 leave 로 폴백.
                if (desertion.processDesertion(roomId, me.userId())) {
                    return;
                }
            }
        } catch (RoomNotFoundException ignored) {
            // 이미 소멸 — 아래 leaveRoom 이 RoomNotFound 를 동일 처리.
        }
        rooms.leaveRoom(roomId, me.userId());
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
        var state = stateStore.load(roomId)
                .orElseThrow(() -> new ResyncNotAvailableException(roomId));
        TichuMatchState matchState = matchStateStore.load(roomId)
                .orElseGet(() -> TichuMatchState.initial(room.playerIds(), room.targetScore()));
        return new ResyncResponse(
                roomId,
                TichuStateMapper.phaseName(state),
                stateStore.currentSeq(roomId),
                TichuStateMapper.toTableView(state, matchState.scoresByTeam(),
                        matchState.roundNumber()),
                // 관전자는 손패 없음 — TableView 만 받음.
                seat >= 0 ? TichuStateMapper.toPrivateHand(state, seat) : null,
                disconnectedSeats(room, me.userId()),
                chipStore.stacks(roomId)); // D-82 — 방 칩 스택(입장/재접속 시 즉시 표시).
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

    public record ResyncResponse(
            String roomId,
            String phase,
            long eventSeq,
            TableView tableView,
            PrivateHand privateHand,
            List<Integer> disconnectedSeats,
            // D-82 — 방 단위 테이블 칩 스택(userId→칩). 내기 없는 방은 빈 맵.
            java.util.Map<Long, Long> chips) {
    }
}
