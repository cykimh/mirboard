package com.mirboard.infra.rest.users;

import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.scoring.BestScoreService;
import com.mirboard.domain.game.scoring.HeadToHeadService;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final HeadToHeadService headToHead;
    private final BestScoreService bestScores;

    public UserController(UserRepository userRepo, UserGameStatsService gameStats, GameRegistry games,
                          HeadToHeadService headToHead, BestScoreService bestScores) {
        this.userRepo = userRepo;
        this.gameStats = gameStats;
        this.games = games;
        this.headToHead = headToHead;
        this.bestScores = bestScores;
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
    /** D-154 — 상대 전적(사람만, 게스트·봇 제외). gameType 을 주면 그 게임만. 많이 붙어 본 상대부터. */
    @GetMapping("/{userId}/head-to-head")
    public HeadToHeadResponse headToHead(@PathVariable long userId,
                                         @RequestParam(required = false) String gameType) {
        return new HeadToHeadResponse(headToHead.against(userId, gameType));
    }

    public record HeadToHeadResponse(List<HeadToHeadService.Entry> entries) {
    }

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
                        // D-164 목록 순위 왕관 + D-198 명예 계급 — 서비스가 정한다.
                        r.tier(),
                        r.stats().winCount(),
                        r.stats().loseCount(),
                        r.stats().desertCount()))
                .toList();
        return new RankingResponse(game, entries);
    }

    /**
     * D-160 — 랭킹 탭 순서: 전체에서 많이 한 게임부터(게임별 승+패 합, 봇 제외). 한 판도 없는 게임은 싣지 않는다 — 클라가
     * 카탈로그 순서로 뒤에 붙인다.
     */
    @GetMapping("/ranking/games")
    public GamePlaysResponse rankingGames() {
        return new GamePlaysResponse(gameStats.playsByGame().stream()
                .map(p -> new GamePlays(p.gameType(), p.plays()))
                .toList());
    }

    public record GamePlays(String gameType, long plays) {
    }

    public record GamePlaysResponse(List<GamePlays> games) {
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
        // D-201 — 최고 점수를 내는 게임은 그 값을 싣고, 최고 점수만 있는 게임(혼자만 한 요트)은 0승 0패 줄로 덧붙인다.
        Map<String, Integer> best = bestScores.bestScores(u);
        List<GameRecord> played = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GameStats g : gameStats.playedGames(u.getId())) {
            seen.add(g.gameType());
            played.add(new GameRecord(g.gameType(), g.rating(), gameStats.tierOf(u, g),
                    g.winCount(), g.loseCount(), g.desertCount(), best.get(g.gameType())));
        }
        best.forEach((gameType, score) -> {
            if (seen.add(gameType)) {
                GameStats g = gameStats.get(u.getId(), gameType);
                played.add(new GameRecord(gameType, g.rating(), gameStats.tierOf(u, g),
                        g.winCount(), g.loseCount(), g.desertCount(), score));
            }
        });
        return new UserStatsResponse(
                u.getId(),
                u.getUsername(),
                legacy.winCount(),
                legacy.loseCount(),
                legacy.rating(),
                gameStats.tierOf(u, legacy),
                legacy.desertCount(),
                played,
                gameStats.fixedTier(u));
    }

    /**
     * @param games 한 판이라도 한 게임별 전적 (D-115) + 최고 점수만 있는 게임(D-201). 최상위 필드는 TICHU 호환 값.
     * @param fixedTier D-198 — 모든 게임에서 이 계급으로 보이는 명예 계정이면 그 계급, 아니면 null. {@code games[]} 에 없는 게임에도 쓴다.
     */
    public record UserStatsResponse(
            long userId,
            String username,
            int winCount,
            int loseCount,
            int rating,
            String tier,
            int desertCount,
            List<GameRecord> games,
            String fixedTier) {
    }

    /** @param bestScore D-201 — 개인 최고 점수. 점수를 겨루는 게임(요트)만, 셀 판이 없거나 게스트면 null. */
    public record GameRecord(
            String gameType,
            int rating,
            String tier,
            int winCount,
            int loseCount,
            int desertCount,
            Integer bestScore) {
    }
}
