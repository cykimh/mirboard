package com.mirboard.infra.rest.admin;

import com.mirboard.domain.admin.AdminAuthorization;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.RoomService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * D-86 — 어드민 전용 엔드포인트(/api/admin/**). 권한 판정은 {@link AdminAuthorization} 에
 * 위임만 하고(규칙#4 — 컨트롤러에 룰 로직 없음), 실제 동작은 도메인 서비스를 호출한다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminAuthorization adminAuth;
    private final RoomService roomService;

    public AdminController(AdminAuthorization adminAuth, RoomService roomService) {
        this.adminAuth = adminAuth;
        this.roomService = roomService;
    }

    /** 진행 중 매치 강제 종료(host 검증 없음). 비어드민은 403 NOT_ADMIN. */
    @PostMapping("/rooms/{roomId}/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abortRoom(@AuthenticationPrincipal AuthPrincipal principal,
                          @PathVariable String roomId) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        adminAuth.requireAdmin(principal.userId());
        roomService.adminAbortGame(roomId);
    }
}
