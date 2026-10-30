package com.mirboard.infra.rest.rooms;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.ws.HostKickService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** D-162 — 게임 중 끊긴 사람을 방장이 내보낸다(바로 탈주 처리). 판정과 처리는 {@link HostKickService}. */
@RestController
@RequestMapping("/api/rooms")
public class RoomKickController {

    private final HostKickService kicks;

    public RoomKickController(HostKickService kicks) {
        this.kicks = kicks;
    }

    @PostMapping("/{roomId}/kick")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@PathVariable String roomId,
                     @AuthenticationPrincipal AuthPrincipal me,
                     @RequestBody @Valid KickRequest req) {
        kicks.kick(roomId, me.userId(), req.userId());
    }

    public record KickRequest(@NotNull Long userId) {
    }
}
