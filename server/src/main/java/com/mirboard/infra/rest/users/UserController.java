package com.mirboard.infra.rest.users;

import com.mirboard.domain.game.scoring.Tier;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 8D — 유저 통계 조회. rating + win/lose 누적 + 파생 tier 반환. 식별 정보
 * (email/phone 등) 는 절대 노출 안 함 (D-02 schema constraint).
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepo;

    public UserController(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @GetMapping("/{userId}/stats")
    public ResponseEntity<UserStatsResponse> stats(@PathVariable long userId) {
        return userRepo.findById(userId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private UserStatsResponse toResponse(User u) {
        return new UserStatsResponse(
                u.getId(),
                u.getUsername(),
                u.getWinCount(),
                u.getLoseCount(),
                u.getRating(),
                Tier.fromRating(u.getRating()).name());
    }

    public record UserStatsResponse(
            long userId,
            String username,
            int winCount,
            int loseCount,
            int rating,
            String tier) {
    }
}
