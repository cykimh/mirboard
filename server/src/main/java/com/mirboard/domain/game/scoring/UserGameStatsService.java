package com.mirboard.domain.game.scoring;

import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private final UserMonthlyStatsRepository monthly;
    private final UserRepository users;
    private final HonoraryTierPolicy honorary;
    private final Clock clock;

    public UserGameStatsService(UserGameStatsRepository repo, UserMonthlyStatsRepository monthly,
                                UserRepository users, HonoraryTierPolicy honorary, Clock clock) {
        this.repo = repo;
        this.monthly = monthly;
        this.users = users;
        this.honorary = honorary;
        this.clock = clock;
    }

    /** D-209 — 이번 달 전적에 남기는 한 판의 결과. {@code code} 는 최근 결과 문자열의 한 글자. */
    public enum Outcome {
        WIN("W"), LOSS("L"), DRAW("D");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
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

    /**
     * D-199 — 이번 달 랭킹 한 줄. 승·패·탈주·최근 결과는 이번 달 것이고, 계급·점수는 누적(D-115)에서 온다.
     *
     * @param recent       이번 달 최근 결과, 오래된 것 → 최근, 최대 5글자(W·L·D)
     * @param rating       계급의 근거가 된 게임의 누적 레이팅
     * @param tier         순위까지 본 계급(D-164) — 명예 계정이면 {@link HonoraryTierPolicy#HONORARY_TIER}(D-198)
     * @param tierGameType 계급의 근거가 된 게임. 게임별 랭킹이면 그 게임, 전체면 그 사람이 가장 많이 한 게임(없으면 null)
     */
    public record MonthlyRankRow(int rank, long userId, String username, int winCount, int loseCount,
                                 int desertCount, String recent, int rating, String tier, String tierGameType) {

        /** 승률(%) 반올림. 승·패가 없으면(무승부만) null. */
        public Integer winRate() {
            int games = winCount + loseCount;
            return games == 0 ? null : (int) Math.round(winCount * 100.0 / games);
        }
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
        recordMonthly(userId, gameType, StatsMonth.current(clock), win ? Outcome.WIN : Outcome.LOSS, deserted);
    }

    /**
     * 무승부 1건 — 승·패·탈주 수와 rating 을 바꾸지 않고 행만 남긴다(D-178). 매치 기록기 계약 2(비봇 참가자마다 행 — 게스트 정리가
     * "매치를 끝낸 증거"로 쓴다)를 무승부에서도 지키려고. {@link #record} 의 win=false 는 패로 세므로 쓰지 않는다.
     */
    @Transactional
    public void recordDraw(long userId, String gameType) {
        repo.record(userId, gameType, null, 0, 0, 0);
        recordMonthly(userId, gameType, StatsMonth.current(clock), Outcome.DRAW, false);
    }

    /**
     * D-209 — 그 달의 전적에 한 판을 더한다: 그 게임 행과 전체({@link UserMonthlyStats#ALL_GAMES}) 행 둘. 누적 전적을 쓰는
     * {@link #record}·{@link #recordDraw} 가 같은 트랜잭션에서 부르므로 게스트·봇·1인 판 제외 규칙이 누적 전적과 같다(기록기가
     * 부르지 않으면 여기도 쌓이지 않는다). 달을 받는 것은 달 경계를 시험하려는 것 — 운영 경로는 {@link StatsMonth#current}.
     *
     * @param month 그 달의 1일(Asia/Seoul)
     */
    @Transactional
    public void recordMonthly(long userId, String gameType, LocalDate month, Outcome outcome, boolean deserted) {
        int win = outcome == Outcome.WIN ? 1 : 0;
        int lose = outcome == Outcome.LOSS ? 1 : 0;
        int desert = deserted ? 1 : 0;
        monthly.record(userId, gameType, month, win, lose, desert, outcome.code());
        monthly.record(userId, UserMonthlyStats.ALL_GAMES, month, win, lose, desert, outcome.code());
    }

    /** D-160 — 랭킹 탭 순서용 게임별 판수(많이 한 게임부터). */
    @Transactional(readOnly = true)
    public List<UserGameStatsRepository.GamePlays> playsByGame() {
        return repo.countPlaysByGame();
    }

    /** 이번 달(Asia/Seoul)의 1일. */
    public LocalDate currentMonth() {
        return StatsMonth.current(clock);
    }

    /**
     * D-199 — 한 달의 랭킹. 그 달에 한 판이라도 기록된 사람만, 승수 → 승률 → 판수 순(봇·게스트 제외).
     *
     * @param gameType 게임 id. null 이면 전체(모든 게임 합)
     * @param month    그 달의 1일(Asia/Seoul) — 보통 {@link #currentMonth()}
     */
    @Transactional(readOnly = true)
    public List<MonthlyRankRow> monthlyRanking(String gameType, LocalDate month, int limit) {
        String key = gameType == null ? UserMonthlyStats.ALL_GAMES : gameType;
        // D-117 — 일회용 게스트는 랭킹에 싣지 않는다.
        List<UserMonthlyStats> rows = monthly.findRanking(key, month, GuestPolicy.LIKE_PATTERN,
                PageRequest.of(0, limit));
        List<Long> ids = rows.stream().map(UserMonthlyStats::getUserId).toList();
        Map<Long, User> byId = users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        // 계급은 누적 레이팅에서(D-164·D-198) — 달이 바뀌어도 그대로다.
        Map<Long, List<UserGameStats>> cumulative = ids.isEmpty() ? Map.of()
                : repo.findByUserIdIn(ids).stream().collect(Collectors.groupingBy(UserGameStats::getUserId));

        List<MonthlyRankRow> out = new ArrayList<>(rows.size());
        for (UserMonthlyStats row : rows) {
            User user = byId.get(row.getUserId());
            if (user == null) {
                continue;
            }
            GameStats basis = tierBasis(gameType, cumulative.getOrDefault(row.getUserId(), List.of()));
            String tier = basis == null
                    ? honorary.apply(user, Tier.fromRating(DEFAULT_RATING).name())
                    : tierOf(user, basis);
            out.add(new MonthlyRankRow(out.size() + 1, row.getUserId(), user.getUsername(),
                    row.getWinCount(), row.getLoseCount(), row.getDesertCount(), row.getRecent(),
                    basis == null ? DEFAULT_RATING : basis.rating(), tier,
                    basis == null ? null : basis.gameType()));
        }
        return out;
    }

    /**
     * 랭킹 한 줄의 계급 근거. 게임별이면 그 게임의 누적 전적, 전체면 가장 많이 한 게임(승+패, 동률은 게임 id 순 — 클라 헤더의
     * {@code primaryGame} 과 같은 규칙). 승·패가 한 판도 없으면 null.
     */
    private static GameStats tierBasis(String gameType, List<UserGameStats> cumulative) {
        if (gameType != null) {
            return cumulative.stream().filter(s -> s.getGameType().equals(gameType))
                    .findFirst().map(GameStats::of).orElseGet(() -> GameStats.initial(gameType));
        }
        return cumulative.stream().map(GameStats::of)
                .filter(g -> g.gamesPlayed() > 0)
                .min(Comparator.comparingInt((GameStats g) -> -g.gamesPlayed()).thenComparing(GameStats::gameType))
                .orElse(null);
    }
}
