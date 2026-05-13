package com.mirboard.infra.rest.rooms;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping
    public ListResponse list(@RequestParam(required = false) String gameType,
                             @RequestParam(required = false, defaultValue = "WAITING") RoomStatus status) {
        // MVP: only WAITING rooms are enumerable (via rooms:open ZSET).
        // IN_GAME/FINISHED enumeration would require Redis SCAN — out of scope.
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

    public record CreateRequest(@NotBlank String name, @NotBlank String gameType) {
    }

    public record ListResponse(List<Room> rooms) {
    }
}
