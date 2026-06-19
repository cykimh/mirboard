package com.mirboard.infra.rest.me;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/me")
public class MeController {

    /** D-81 — 무료 충전: 잔액이 이 값 미만일 때만 BASE 로 충전(빈털터리 방지). */
    private static final long TOPUP_THRESHOLD = 200;
    private static final long TOPUP_BASE = 500;

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
                user.getWinCount(), user.getLoseCount(), user.getChipBalance());
    }

    /**
     * D-81 — 가상 칩 무료 충전. 잔액이 {@code TOPUP_THRESHOLD} 미만일 때만
     * {@code TOPUP_BASE} 로 올린다(이미 충분하면 no-op). 현금과 무관한 게임 재화.
     */
    @PostMapping("/chips/topup")
    @Transactional
    public MeResponse topUpChips(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        userRepository.topUpChips(principal.userId(), TOPUP_THRESHOLD, TOPUP_BASE);
        return me(principal);
    }

    public record MeResponse(long userId, String username, int winCount, int loseCount,
            long chipBalance) {
    }
}
