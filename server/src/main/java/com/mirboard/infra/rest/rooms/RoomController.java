package com.mirboard.infra.rest.rooms;

import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.PrivateHand;
import com.mirboard.domain.game.tichu.state.TableView;
import com.mirboard.domain.game.tichu.state.TichuStateMapper;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.ResyncNotAvailableException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    public RoomController(RoomService rooms,
                          TichuGameStateStore stateStore,
                          TichuMatchStateStore matchStateStore) {
        this.rooms = rooms;
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
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
        return rooms.createRoom(me.userId(), req.name(), req.gameType());
    }

    @GetMapping("/{roomId}")
    public Room get(@PathVariable String roomId) {
        return rooms.getRoom(roomId);
    }

    @PostMapping("/{roomId}/join")
    public Room join(@PathVariable String roomId,
                     @AuthenticationPrincipal AuthPrincipal me) {
        return rooms.joinRoom(roomId, me.userId());
    }

    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String roomId,
                      @AuthenticationPrincipal AuthPrincipal me) {
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
                .orElseGet(() -> TichuMatchState.initial(room.playerIds()));
        return new ResyncResponse(
                roomId,
                TichuStateMapper.phaseName(state),
                stateStore.currentSeq(roomId),
                TichuStateMapper.toTableView(state, matchState.scoresByTeam(),
                        matchState.roundNumber()),
                // 관전자는 손패 없음 — TableView 만 받음.
                seat >= 0 ? TichuStateMapper.toPrivateHand(state, seat) : null);
    }

    public record CreateRequest(@NotBlank String name, @NotBlank String gameType) {
    }

    public record ListResponse(List<Room> rooms) {
    }

    public record ResyncResponse(
            String roomId,
            String phase,
            long eventSeq,
            TableView tableView,
            PrivateHand privateHand) {
    }
}
