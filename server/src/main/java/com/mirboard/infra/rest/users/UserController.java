package com.mirboard.infra.rest.users;

import com.mirboard.domain.game.scoring.Tier;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Phase 16(#5) — 유저 랭킹 (봇 제외, rating 내림차순). limit 1~100.
     * 식별 정보는 username 만 (D-02 schema constraint).
     */
    @GetMapping("/ranking")
    public RankingResponse ranking(@RequestParam(defaultValue = "20") int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<User> users = userRepo.findRanking(PageRequest.of(0, capped));
        List<RankEntry> entries = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            User u = users.get(i);
            entries.add(new RankEntry(
                    i + 1,
                    u.getId(),
                    u.getUsername(),
                    u.getRating(),
                    Tier.fromRating(u.getRating()).name(),
                    u.getWinCount(),
                    u.getLoseCount()));
        }
        return new RankingResponse(entries);
    }

    public record RankEntry(
            int rank,
            long userId,
            String username,
            int rating,
            String tier,
            int winCount,
            int loseCount) {
    }

    public record RankingResponse(List<RankEntry> entries) {
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
