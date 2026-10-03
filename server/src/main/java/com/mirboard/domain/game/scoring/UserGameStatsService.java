package com.mirboard.domain.game.scoring;

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

    public UserGameStatsService(UserGameStatsRepository repo, UserRepository users) {
        this.repo = repo;
        this.users = users;
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

        public String tier() {
            return Tier.fromRating(rating).name();
        }
    }

    public record RankRow(int rank, long userId, String username, GameStats stats) {
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
     * @param newRating ELO 를 적용하지 않는 매치(봇 포함)면 null — rating 유지
     */
    @Transactional
    public void record(long userId, String gameType, boolean win, Integer newRating, boolean deserted) {
        repo.record(userId, gameType, newRating, win ? 1 : 0, win ? 0 : 1, deserted ? 1 : 0);
    }

    @Transactional(readOnly = true)
    public List<RankRow> ranking(String gameType, int limit) {
        List<UserGameStats> rows = repo.findRanking(gameType, PageRequest.of(0, limit));
        Map<Long, String> names = users.findAllById(rows.stream().map(UserGameStats::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        return IntStream.range(0, rows.size())
                .mapToObj(i -> new RankRow(i + 1, rows.get(i).getUserId(),
                        names.getOrDefault(rows.get(i).getUserId(), "?"), GameStats.of(rows.get(i))))
                .toList();
    }
}
