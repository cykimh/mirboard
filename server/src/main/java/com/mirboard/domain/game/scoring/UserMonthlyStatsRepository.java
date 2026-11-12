package com.mirboard.domain.game.scoring;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserMonthlyStatsRepository extends JpaRepository<UserMonthlyStats, UserMonthlyStats.Pk> {

    /**
     * D-199 — 이번 달 랭킹. 봇·게스트(D-117) 제외. 순서는 승수 → 승률 → 판수 → 먼저 가입한 순.
     *
     * <p>승수가 같으면 승률이 높은 쪽 = 패가 적은 쪽이다. 승수가 0 이면 승률이 모두 0 이라 판수(= 패)가 많은 쪽이 먼저다. 그래서
     * 나눗셈 없이(0판 나누기 없음) 패 수의 방향만 바꿔 정렬한다.
     */
    @Query("SELECT m FROM UserMonthlyStats m, User u"
            + " WHERE u.id = m.userId AND u.isBot = false AND m.gameType = :gameType AND m.statMonth = :month"
            + " AND u.username NOT LIKE :guestPattern"
            + " ORDER BY m.winCount DESC,"
            + " CASE WHEN m.winCount = 0 THEN -m.loseCount ELSE m.loseCount END ASC,"
            + " m.userId ASC")
    List<UserMonthlyStats> findRanking(@Param("gameType") String gameType,
                                       @Param("month") LocalDate month,
                                       @Param("guestPattern") String guestPattern,
                                       Pageable pageable);

    /**
     * 한 매치 결과를 그 달 행에 누적한다. {@code result}(W·L·D 한 글자)는 최근 결과 끝에 붙이고 5글자만 남긴다. 판정과 증분이 한
     * 문장이라 같은 유저의 동시 기록에도 갱신이 사라지지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO user_monthly_stats
                (user_id, game_type, stat_month, win_count, lose_count, desert_count, recent, updated_at)
            VALUES (:userId, :gameType, :month, :win, :lose, :desert, :result, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, game_type, stat_month) DO UPDATE SET
                win_count    = user_monthly_stats.win_count + EXCLUDED.win_count,
                lose_count   = user_monthly_stats.lose_count + EXCLUDED.lose_count,
                desert_count = user_monthly_stats.desert_count + EXCLUDED.desert_count,
                recent       = RIGHT(user_monthly_stats.recent || EXCLUDED.recent, 5),
                updated_at   = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void record(@Param("userId") long userId,
                @Param("gameType") String gameType,
                @Param("month") LocalDate month,
                @Param("win") int win,
                @Param("lose") int lose,
                @Param("desert") int desert,
                @Param("result") String result);
}
