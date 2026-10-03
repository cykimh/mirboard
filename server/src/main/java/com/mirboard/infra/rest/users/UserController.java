package com.mirboard.infra.rest.users;

import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.util.List;
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
 *
 * <p>D-115 — 전적은 게임별(`user_game_stats`)이다. 응답의 최상위 필드는 기존 클라 호환을
 * 위해 TICHU 값을 그대로 두고, 게임별 목록은 {@code games[]} 로 준다.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** 최상위 호환 필드와 랭킹 기본값이 가리키는 게임 (D-115 이전엔 이 게임뿐이었다). */
    static final String LEGACY_GAME = "TICHU";

    private final UserRepository userRepo;
    private final UserGameStatsService gameStats;
    private final GameRegistry games;

    public UserController(UserRepository userRepo, UserGameStatsService gameStats, GameRegistry games) {
        this.userRepo = userRepo;
        this.gameStats = gameStats;
        this.games = games;
    }

    @GetMapping("/{userId}/stats")
    public ResponseEntity<UserStatsResponse> stats(@PathVariable long userId) {
        return userRepo.findById(userId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /**
     * 좌석/참가자 표시용 userId→username 일괄 조회. `?ids=1,2,3` (최대 50개).
     * username 은 공개 정보(랭킹에도 노출)이며 그 외 식별 정보는 반환하지 않음(D-02).
     */
    @GetMapping("/names")
    public NamesResponse names(@RequestParam List<Long> ids) {
        List<Long> distinct = ids.stream().distinct().limit(50).toList();
        List<UserName> names = userRepo.findAllById(distinct).stream()
                .map(u -> new UserName(u.getId(), u.getUsername()))
                .toList();
        return new NamesResponse(names);
    }

    public record UserName(long userId, String username) {
    }

    public record NamesResponse(List<UserName> names) {
    }

    /**
     * Phase 16(#5) — 유저 랭킹 (봇 제외, rating 내림차순). limit 1~100.
     * 식별 정보는 username 만 (D-02 schema constraint).
     *
     * <p>D-115 — 게임별이다. {@code gameType} 생략 시 TICHU(기존 호출 호환). 그 게임을
     * 한 판이라도 한 사람만 싣는다. 등록되지 않은 게임이면 404.
     */
    @GetMapping("/ranking")
    public RankingResponse ranking(@RequestParam(defaultValue = "20") int limit,
                                   @RequestParam(defaultValue = LEGACY_GAME) String gameType) {
        String game = games.require(gameType.toUpperCase(java.util.Locale.ROOT)).id();
        int capped = Math.max(1, Math.min(limit, 100));
        List<RankEntry> entries = gameStats.ranking(game, capped).stream()
                .map(r -> new RankEntry(
                        r.rank(),
                        r.userId(),
                        r.username(),
                        r.stats().rating(),
                        r.stats().tier(),
                        r.stats().winCount(),
                        r.stats().loseCount(),
                        r.stats().desertCount()))
                .toList();
        return new RankingResponse(game, entries);
    }

    public record RankEntry(
            int rank,
            long userId,
            String username,
            int rating,
            String tier,
            int winCount,
            int loseCount,
            int desertCount) {
    }

    public record RankingResponse(String gameType, List<RankEntry> entries) {
    }

    private UserStatsResponse toResponse(User u) {
        GameStats legacy = gameStats.get(u.getId(), LEGACY_GAME);
        List<GameRecord> played = gameStats.playedGames(u.getId()).stream()
                .map(g -> new GameRecord(g.gameType(), g.rating(), g.tier(),
                        g.winCount(), g.loseCount(), g.desertCount()))
                .toList();
        return new UserStatsResponse(
                u.getId(),
                u.getUsername(),
                legacy.winCount(),
                legacy.loseCount(),
                legacy.rating(),
                legacy.tier(),
                legacy.desertCount(),
                played);
    }

    /**
     * @param games 한 판이라도 한 게임별 전적 (D-115). 최상위 필드는 TICHU 호환 값.
     */
    public record UserStatsResponse(
            long userId,
            String username,
            int winCount,
            int loseCount,
            int rating,
            String tier,
            int desertCount,
            List<GameRecord> games) {
    }

    public record GameRecord(
            String gameType,
            int rating,
            String tier,
            int winCount,
            int loseCount,
            int desertCount) {
    }
}
