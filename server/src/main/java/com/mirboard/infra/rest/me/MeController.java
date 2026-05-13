package com.mirboard.infra.rest.me;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository userRepository;

    public MeController(UserRepository userRepository) {
        this.userRepository = userRepository;
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

    public record MeResponse(long userId, String username, int winCount, int loseCount) {
    }
}
