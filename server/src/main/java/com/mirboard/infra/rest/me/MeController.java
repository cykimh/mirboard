package com.mirboard.infra.rest.me;

import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.AuthService;
import com.mirboard.domain.lobby.auth.UserRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
    private final UserGameStatsService gameStats;

    public MeController(UserRepository userRepository, AuthService authService,
                        UserGameStatsService gameStats) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.gameStats = gameStats;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        var user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED));
        // D-115 — 승패는 게임별 전적의 합. 게임별 값은 /api/users/{id}/stats 의 games[].
        List<GameStats> played = gameStats.playedGames(user.getId());
        return new MeResponse(user.getId(), user.getUsername(),
                played.stream().mapToInt(GameStats::winCount).sum(),
                played.stream().mapToInt(GameStats::loseCount).sum());
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
