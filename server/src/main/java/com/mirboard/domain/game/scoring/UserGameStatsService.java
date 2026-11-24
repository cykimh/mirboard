package com.mirboard.domain.game.scoring;

import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-115 — 게임별 전적·레이팅의 읽기/쓰기 창구. 게임 이름을 모른다 — 호출자가
 * {@code GameDefinition.id()} 를 넘긴다.
 *
 * <p>D-305 — <b>계급은 통합 레이팅 하나</b>에서 나온다: {@code user_game_stats} 의 {@link UserGameStats#ALL_GAMES} 행
 * ({@link #unified}). 기록기는 ELO 입력을 {@link #ratingInput} 에서 읽어 <b>증감</b>을 계산하고,
 * {@link #record}·{@link #recordDraw} 가 통합 행과 게임 행에 그 증감을 더한다(절대값 저장 없음 — 동시 기록에 안전). 게임별 rating 은 기록용이고 계급에 쓰지 않는다.
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

        /** 누적 판 수(승+패). 통합 행이면 모든 게임 합 — K-factor 판정에 쓴다. */
        public int gamesPlayed() {
            return winCount + loseCount;
        }

    }

    /**
     * D-199 — 이번 달 랭킹 한 줄. 승·패·탈주·최근 결과는 이번 달 것이고, 계급·점수는 통합 레이팅(D-305)에서 온다.
     *
     * @param recent 이번 달 최근 결과, 오래된 것 → 최근, 최대 5글자(W·L·D)
     * @param rating 통합 레이팅
     * @param tier   순위까지 본 통합 계급(D-164) — 명예 계정이면 {@link HonoraryTierPolicy#HONORARY_TIER}(D-198)
     */
    public record MonthlyRankRow(int rank, long userId, String username, int winCount, int loseCount,
                                 int desertCount, String recent, int rating, String tier) {

        /** 승률(%) 반올림. 승·패가 없으면(무승부만) null. */
        public Integer winRate() {
            int games = winCount + loseCount;
            return games == 0 ? null : (int) Math.round(winCount * 100.0 / games);
        }
    }

    /**
     * D-164·D-305 — 순위까지 본 계급(통합 레이팅). 점수로 금훈장(1400+)이면 나보다 통합 레이팅이 높은 사람 수로 순위를 구해
     * 왕관을 정한다. 그 아래는 점수 구간 그대로라 조회가 없다. 계급을 내보내는 출구는 전부 이 메서드를 거친다(D-198).
     */
    @Transactional(readOnly = true)
    public String tierOf(User user) {
        return tierOf(user, unified(user.getId()).rating());
    }

    private String tierOf(User user, int unifiedRating) {
        // D-198 — 명예 계정은 순위를 셀 것도 없이 고정 계급(표시만 — 레이팅·순위는 그대로).
        if (honorary.isHonorary(user)) {
            return HonoraryTierPolicy.HONORARY_TIER.name();
        }
        Tier base = Tier.fromRating(unifiedRating);
        if (base != Tier.GOLD_BADGE) {
            return base.name();
        }
        long above = repo.countRankedAbove(UserGameStats.ALL_GAMES, GuestPolicy.LIKE_PATTERN, unifiedRating);
        return Tier.withRank(unifiedRating, (int) Math.min(above + 1, Integer.MAX_VALUE)).name();
    }

    /**
     * D-198 — 고정된 계급(명예 계정만, 아니면 null). D-305 로 계급이 통합 하나가 돼 {@link #tierOf} 만으로 충분하지만 옛 클라가
     * 읽으므로 전적 응답에 계속 싣는다.
     */
    public String fixedTier(User user) {
        return honorary.isHonorary(user) ? HonoraryTierPolicy.HONORARY_TIER.name() : null;
    }

    /**
     * D-305 — 통합 레이팅 행. 행이 없으면(한 판도 안 했거나 게스트·봇) 1000·0판. 승·패·탈주는 모든 게임 합이다.
     */
    @Transactional(readOnly = true)
    public GameStats unified(long userId) {
        return get(userId, UserGameStats.ALL_GAMES);
    }

    /**
     * D-305 — 기록기가 ELO 계산에 넣는 값: 통합 레이팅과 전체 판 수(K 판정). 게스트·봇은 통합 행이 없어 늘 1000·0판이다
     * (D-299 — 저장하지 않는다).
     */
    @Transactional(readOnly = true)
    public EloCalculator.PlayerInput ratingInput(long userId) {
        GameStats u = unified(userId);
        return new EloCalculator.PlayerInput(userId, u.rating(), u.gamesPlayed());
    }

    /** 안 해 본 게임이면 기본값. */
    @Transactional(readOnly = true)
    public GameStats get(long userId, String gameType) {
        return repo.findById(new UserGameStats.Pk(userId, gameType))
                .map(GameStats::of)
                .orElseGet(() -> GameStats.initial(gameType));
    }

    /** 한 판이라도 기록된 게임만(통합 행은 게임이 아니라 없다). */
    @Transactional(readOnly = true)
    public List<GameStats> playedGames(long userId) {
        return repo.findGamesOf(userId).stream().map(GameStats::of).toList();
    }

    /**
     * 한 매치 결과 1건을 누적한다 — 참가 점수 없이(ELO 미적용 판). 기록기는 배율을 받는 아래 것을 쓴다.
     *
     * @param ratingDelta 통합 레이팅 <b>증감</b>. ELO 를 계산하지 않은 판이면 null — rating 유지
     */
    @Transactional
    public void record(long userId, String gameType, boolean win, Integer ratingDelta, boolean deserted) {
        record(userId, gameType, win, ratingDelta, deserted, RatedMatchPolicy.UNRATED);
    }

    /**
     * 한 매치 결과 1건을 누적한다. D-301 — 참가 점수({@link EloCalculator#participationBonus})를 ELO 증감에 더해 저장한다.
     * 아홉 게임 기록기가 모두 이 길을 쓴다(참가 점수를 기록기에서 따로 더하지 말 것).
     *
     * <p>D-305 — {@code ratingDelta} 는 {@link #ratingInput} 의 통합 레이팅으로 계산한 <b>증감</b>
     * ({@link EloCalculator#matchDeltas}·{@link EloCalculator#freeForAllDeltas})이다. 그 증감(+ 참가 점수)을 통합 행과 게임 행에
     * 똑같이 <b>더한다</b>({@code rating = rating + 증감} 한 문장) — 통합 행의 승·패·탈주도 같이 쌓는다(이번 달 전적까지 한 곳,
     * D-199). <b>새 레이팅(절대값)을 받지 않는다</b>: 같은 사람의 두 판(다른 방)이 거의 동시에 끝나면 둘 다 같은 입력값으로
     * 계산하므로, 절대값으로 쓰거나 기록 시점에 다시 읽어 차이를 내면 뒤 판이 앞 판의 변화를 지우거나 두 번 센다.
     *
     * <p>한 매치 안에서는 <b>userId 오름차순</b>으로 부를 것({@link #seatsInUserIdOrder}) — 두 사람이 같이 낀 두 판이 동시에
     * 끝날 때 통합 행을 서로 반대 순서로 잠가 교착하지 않게.
     *
     * <p>D-299 덧붙임 — <b>게스트의 레이팅은 저장하지 않는다</b>: 게스트가 낀 판도 ELO 를 계산하지만(가입한 사람의 것은 그대로
     * 움직인다) 게스트 본인은 게임 행의 승패만 남고 rating 은 1000 그대로이며 통합 행은 만들지 않는다(참가 점수도 없다).
     *
     * @param ratingDelta ELO 증감(참가 점수를 더하기 전). 계산하지 않은 판이면 null — 참가 점수만 더한다
     * @param eloFactor   이 판의 배율({@link RatedMatchPolicy#eloFactor}) — 0 이면 참가 점수도 없다
     */
    @Transactional
    public void record(long userId, String gameType, boolean win, Integer ratingDelta, boolean deserted,
                       double eloFactor) {
        write(userId, gameType, win ? Outcome.WIN : Outcome.LOSS, ratingDelta, deserted, eloFactor);
    }

    /**
     * D-305 — 좌석 번호를 그 좌석 사람의 userId 오름차순으로. 기록기의 전적 쓰기 루프가 이 순서로 돈다(행 잠금 순서를 매치마다
     * 같게 — 교착 방지).
     */
    public static int[] seatsInUserIdOrder(List<Long> playerIds) {
        return java.util.stream.IntStream.range(0, playerIds.size()).boxed()
                .sorted(java.util.Comparator.comparingLong(playerIds::get))
                .mapToInt(Integer::intValue).toArray();
    }

    /**
     * 무승부 1건 — 승·패·탈주 수와 rating 을 바꾸지 않고 행만 남긴다(D-178). 매치 기록기 계약 2(비봇 참가자마다 행 — 게스트 정리가
     * "매치를 끝낸 증거"로 쓴다)를 무승부에서도 지키려고. {@link #record} 의 win=false 는 패로 세므로 쓰지 않는다.
     */
    @Transactional
    public void recordDraw(long userId, String gameType) {
        recordDraw(userId, gameType, RatedMatchPolicy.UNRATED);
    }

    /**
     * 무승부 1건 + 참가 점수(D-301) — 무승부는 ELO 가 움직이지 않지만 레이팅이 적용되는 판(배율 &gt; 0)이면 참가 점수는 받는다.
     *
     * @param eloFactor 이 판의 배율({@link RatedMatchPolicy#eloFactor})
     */
    @Transactional
    public void recordDraw(long userId, String gameType, double eloFactor) {
        write(userId, gameType, Outcome.DRAW, null, false, eloFactor);
    }

    /** 누적 전적의 유일한 쓰기 경로: 게임 행 + 통합 행(게스트 제외) + 이번 달 전적. 레이팅은 읽지 않고 증감만 더한다. */
    private void write(long userId, String gameType, Outcome outcome, Integer ratingDelta, boolean deserted,
                       double eloFactor) {
        int win = outcome == Outcome.WIN ? 1 : 0;
        int lose = outcome == Outcome.LOSS ? 1 : 0;
        int desert = deserted ? 1 : 0;
        if (isGuest(userId)) {
            repo.record(userId, gameType, 0, win, lose, desert);
        } else {
            int delta = EloCalculator.participationBonus(eloFactor, deserted) + (ratingDelta == null ? 0 : ratingDelta);
            repo.record(userId, gameType, delta, win, lose, desert);
            repo.record(userId, UserGameStats.ALL_GAMES, delta, win, lose, desert);
        }
        recordMonthly(userId, gameType, StatsMonth.current(clock), outcome, deserted);
    }

    /** 게스트(D-117 — username 규약)인지. 게스트의 레이팅은 저장하지 않는다. */
    private boolean isGuest(long userId) {
        return users.findById(userId).map(u -> GuestPolicy.isGuest(u.getUsername())).orElse(false);
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
        // 계급은 통합 레이팅에서(D-164·D-198·D-305) — 달이 바뀌어도, 어느 게임 랭킹이어도 같다.
        Map<Long, Integer> unifiedRatings = ids.isEmpty() ? Map.of()
                : repo.findByGameTypeAndUserIdIn(UserGameStats.ALL_GAMES, ids).stream()
                        .collect(Collectors.toMap(UserGameStats::getUserId, UserGameStats::getRating, (a, b) -> a));

        List<MonthlyRankRow> out = new ArrayList<>(rows.size());
        for (UserMonthlyStats row : rows) {
            User user = byId.get(row.getUserId());
            if (user == null) {
                continue;
            }
            int rating = unifiedRatings.getOrDefault(row.getUserId(), DEFAULT_RATING);
            out.add(new MonthlyRankRow(out.size() + 1, row.getUserId(), user.getUsername(),
                    row.getWinCount(), row.getLoseCount(), row.getDesertCount(), row.getRecent(),
                    rating, tierOf(user, rating)));
        }
        return out;
    }
}
