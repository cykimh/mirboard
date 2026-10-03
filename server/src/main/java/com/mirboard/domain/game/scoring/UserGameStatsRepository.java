package com.mirboard.domain.game.scoring;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserGameStatsRepository extends JpaRepository<UserGameStats, UserGameStats.Pk> {

    List<UserGameStats> findByUserIdOrderByGameTypeAsc(long userId);

    /**
     * 게임별 랭킹 — 봇·게스트(D-117, {@code guestPattern}={@code GuestPolicy.LIKE_PATTERN}) 제외,
     * rating 내림차순(동점은 먼저 가입한 순).
     */
    @Query("SELECT s FROM UserGameStats s, User u"
            + " WHERE u.id = s.userId AND u.isBot = false AND s.gameType = :gameType"
            + " AND u.username NOT LIKE :guestPattern"
            + " ORDER BY s.rating DESC, s.userId ASC")
    List<UserGameStats> findRanking(@Param("gameType") String gameType,
                                    @Param("guestPattern") String guestPattern,
                                    Pageable pageable);

    /**
     * 한 매치 결과를 누적한다. 행이 없으면 기본값(1000)에서 시작하고, {@code rating} 이
     * null 이면(봇 매치 등 ELO 미적용) 현재 rating 을 유지한다. 판정과 증분이 한 문장이라
     * 같은 유저의 동시 기록에도 갱신이 사라지지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO user_game_stats (user_id, game_type, rating, win_count, lose_count, desert_count, updated_at)
            VALUES (:userId, :gameType, COALESCE(CAST(:rating AS INTEGER), 1000), :win, :lose, :desert, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, game_type) DO UPDATE SET
                rating       = COALESCE(CAST(:rating AS INTEGER), user_game_stats.rating),
                win_count    = user_game_stats.win_count + EXCLUDED.win_count,
                lose_count   = user_game_stats.lose_count + EXCLUDED.lose_count,
                desert_count = user_game_stats.desert_count + EXCLUDED.desert_count,
                updated_at   = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void record(@Param("userId") long userId,
                @Param("gameType") String gameType,
                @Param("rating") Integer rating,
                @Param("win") int win,
                @Param("lose") int lose,
                @Param("desert") int desert);
}
