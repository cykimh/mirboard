package com.mirboard.infra.rest.me;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.AuthService;
import com.mirboard.domain.lobby.auth.UserRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository userRepository;
    private final AuthService authService;

    public MeController(UserRepository userRepository, AuthService authService) {
        this.userRepository = userRepository;
        this.authService = authService;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        var user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED));
        return new MeResponse(user.getId(), user.getUsername(),
                user.getWinCount(), user.getLoseCount());
    }

    /** D-85 — 본인 비밀번호 변경. 현재 비번 재검증 후 갱신(스키마 무변경). */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthPrincipal principal,
                               @RequestBody ChangePasswordRequest req) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        authService.changePassword(principal.userId(), req.currentPassword(), req.newPassword());
    }

    public record MeResponse(long userId, String username, int winCount, int loseCount) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }
}
