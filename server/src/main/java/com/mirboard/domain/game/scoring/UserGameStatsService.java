package com.mirboard.domain.game.scoring;

import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-115 — 게임별 전적·레이팅의 읽기/쓰기 창구. 게임 이름을 모른다 — 호출자가
 * {@code GameDefinition.id()} 를 넘긴다.
 */
@Service
public class UserGameStatsService {

    public static final int DEFAULT_RATING = 1000;

    private final UserGameStatsRepository repo;
    private final UserRepository users;
    private final HonoraryTierPolicy honorary;

    public UserGameStatsService(UserGameStatsRepository repo, UserRepository users, HonoraryTierPolicy honorary) {
        this.repo = repo;
        this.users = users;
        this.honorary = honorary;
    }

    public record GameStats(String gameType, int rating, int winCount, int loseCount, int desertCount) {

        static GameStats initial(String gameType) {
            return new GameStats(gameType, DEFAULT_RATING, 0, 0, 0);
        }

        static GameStats of(UserGameStats s) {
            return new GameStats(s.getGameType(), s.getRating(),
                    s.getWinCount(), s.getLoseCount(), s.getDesertCount());
        }

        /** K-factor 판정용 누적 판 수 (티츄 MatchResultRecorder 와 같은 정의). */
        public int gamesPlayed() {
            return winCount + loseCount;
        }

        /** 점수 구간 계급(D-164). 왕관은 순위가 있어야 정해진다 — {@link UserGameStatsService#tierOf}. */
        public String tier() {
            return Tier.fromRating(rating).name();
        }
    }

    /** @param tier 순위까지 본 계급(D-164) — 명예 계정이면 {@link HonoraryTierPolicy#HONORARY_TIER}(D-198). */
    public record RankRow(int rank, long userId, String username, GameStats stats, String tier) {
    }

    /**
     * D-164 — 순위까지 본 계급. 점수로 금훈장(1430+)이면 나보다 높은 사람 수로 순위를 구해 왕관을 정한다. 그 아래는 점수 구간 그대로라
     * 조회가 없다.
     */
    @Transactional(readOnly = true)
    public String tierOf(User user, GameStats stats) {
        // D-198 — 명예 계정은 순위를 셀 것도 없이 별왕관(표시만 — 레이팅·순위는 그대로).
        if (honorary.isHonorary(user)) {
            return HonoraryTierPolicy.HONORARY_TIER.name();
        }
        if (Tier.fromRating(stats.rating()) != Tier.GOLD_BADGE) {
            return stats.tier();
        }
        long above = repo.countRankedAbove(stats.gameType(), GuestPolicy.LIKE_PATTERN, stats.rating());
        return Tier.withRank(stats.rating(), (int) Math.min(above + 1, Integer.MAX_VALUE)).name();
    }

    /**
     * D-198 — 게임과 상관없이 고정된 계급(명예 계정만, 아니면 null). 클라가 그 게임을 아직 안 해 본 사람(전적 줄 없음)에게도 같은 계급을
     * 그리도록 전적 응답에 싣는다.
     */
    public String fixedTier(User user) {
        return honorary.isHonorary(user) ? HonoraryTierPolicy.HONORARY_TIER.name() : null;
    }

    /** 안 해 본 게임이면 기본값. */
    @Transactional(readOnly = true)
    public GameStats get(long userId, String gameType) {
        return repo.findById(new UserGameStats.Pk(userId, gameType))
                .map(GameStats::of)
                .orElseGet(() -> GameStats.initial(gameType));
    }

    /** 한 판이라도 기록된 게임만. */
    @Transactional(readOnly = true)
    public List<GameStats> playedGames(long userId) {
        return repo.findByUserIdOrderByGameTypeAsc(userId).stream().map(GameStats::of).toList();
    }

    /**
     * 한 매치 결과 1건을 누적한다.
     *
     * @param newRating ELO 를 적용하지 않는 매치(봇·게스트 포함, {@link RatedMatchPolicy})면
     *                  null — rating 유지
     */
    @Transactional
    public void record(long userId, String gameType, boolean win, Integer newRating, boolean deserted) {
        repo.record(userId, gameType, newRating, win ? 1 : 0, win ? 0 : 1, deserted ? 1 : 0);
    }

    /**
     * 무승부 1건 — 승·패·탈주 수와 rating 을 바꾸지 않고 행만 남긴다(D-178). 매치 기록기 계약 2(비봇 참가자마다 행 — 게스트 정리가
     * "매치를 끝낸 증거"로 쓴다)를 무승부에서도 지키려고. {@link #record} 의 win=false 는 패로 세므로 쓰지 않는다.
     */
    @Transactional
    public void recordDraw(long userId, String gameType) {
        repo.record(userId, gameType, null, 0, 0, 0);
    }

    /** D-160 — 랭킹 탭 순서용 게임별 판수(많이 한 게임부터). */
    @Transactional(readOnly = true)
    public List<UserGameStatsRepository.GamePlays> playsByGame() {
        return repo.countPlaysByGame();
    }

    @Transactional(readOnly = true)
    public List<RankRow> ranking(String gameType, int limit) {
        // D-117 — 일회용 게스트는 랭킹에 싣지 않는다.
        List<UserGameStats> rows = repo.findRanking(gameType, GuestPolicy.LIKE_PATTERN,
                PageRequest.of(0, limit));
        Map<Long, String> names = users.findAllById(rows.stream().map(UserGameStats::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        return IntStream.range(0, rows.size())
                .mapToObj(i -> {
                    int rank = i + 1;
                    String name = names.getOrDefault(rows.get(i).getUserId(), "?");
                    GameStats stats = GameStats.of(rows.get(i));
                    // D-164 — 목록 순위로 왕관(금훈장 이상). D-198 — 명예 계정은 별왕관(순서는 그대로, 봇은 이 목록에 없다).
                    String tier = honorary.apply(name, Tier.withRank(stats.rating(), rank).name());
                    return new RankRow(rank, rows.get(i).getUserId(), name, stats, tier);
                })
                .toList();
    }
}
