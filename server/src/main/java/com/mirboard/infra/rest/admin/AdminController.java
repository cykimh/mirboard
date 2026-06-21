package com.mirboard.infra.rest.admin;

import com.mirboard.domain.admin.AdminAuthorization;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.SuspensionService;
import com.mirboard.domain.lobby.room.RoomService;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private static final long DEFAULT_SUSPEND_MINUTES = 60L;
    private static final long MAX_SUSPEND_MINUTES = 60L * 24 * 365; // 1년 상한

    private final AdminAuthorization adminAuth;
    private final RoomService roomService;
    private final SuspensionService suspensions;

    public AdminController(AdminAuthorization adminAuth, RoomService roomService,
                          SuspensionService suspensions) {
        this.adminAuth = adminAuth;
        this.roomService = roomService;
        this.suspensions = suspensions;
    }

    /** 진행 중 매치 강제 종료(host 검증 없음). 비어드민은 403 NOT_ADMIN. */
    @PostMapping("/rooms/{roomId}/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abortRoom(@AuthenticationPrincipal AuthPrincipal principal,
                          @PathVariable String roomId) {
        adminAuth.requireAdmin(requireUserId(principal));
        roomService.adminAbortGame(roomId);
    }

    /** 유저 정지(Redis TTL, users 비침범). 정지 후 로그인/CONNECT 차단. */
    @PostMapping("/users/{userId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspendUser(@AuthenticationPrincipal AuthPrincipal principal,
                            @PathVariable long userId,
                            @RequestBody(required = false) SuspendRequest req) {
        adminAuth.requireAdmin(requireUserId(principal));
        long minutes = req != null && req.minutes() != null ? req.minutes() : DEFAULT_SUSPEND_MINUTES;
        if (minutes < 1) minutes = 1;
        if (minutes > MAX_SUSPEND_MINUTES) minutes = MAX_SUSPEND_MINUTES;
        suspensions.suspend(userId, Duration.ofMinutes(minutes));
    }

    /** 유저 정지 해제. */
    @DeleteMapping("/users/{userId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsuspendUser(@AuthenticationPrincipal AuthPrincipal principal,
                              @PathVariable long userId) {
        adminAuth.requireAdmin(requireUserId(principal));
        suspensions.unsuspend(userId);
    }

    private static long requireUserId(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        return principal.userId();
    }

    public record SuspendRequest(Long minutes) {
    }
}
