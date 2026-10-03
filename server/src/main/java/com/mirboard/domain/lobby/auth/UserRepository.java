package com.mirboard.domain.lobby.auth;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Phase 9A — 시드 봇 4명 조회. id 오름차순으로 안정 정렬. */
    @Query("SELECT u FROM User u WHERE u.isBot = true ORDER BY u.id ASC")
    List<User> findBots();

    /**
     * D-117 — 매치 참가자 중 게스트가 있는지(ELO 판정, {@code RatedMatchPolicy}).
     * {@code prefix} 는 {@link GuestPolicy#PREFIX}.
     */
    boolean existsByIdInAndUsernameStartingWith(Collection<Long> ids, String prefix);

    /**
     * D-117 — 정리 대상 게스트 id. 보존 기간이 지났고({@code created_at < cutoff}) 남긴 흔적이
     * 없는 행만: 끝낸 매치(user_game_stats — D-115 계약상 비봇 참가자마다 생김), 채팅 신고
     * (신고자·피신고자 양쪽), 어드민 역할. {@code afterId} 커서 뒤에서 id 순으로 최대 {@code batch}.
     *
     * <p>users 를 참조하는 FK 테이블을 새로 만들면 여기에도 NOT EXISTS 를 더할 것 — 빠뜨리면
     * 그 행은 삭제 때 FK 위반으로 매번 건너뛰어진다(warn).
     */
    @Query(value = """
            SELECT u.id FROM users u
            WHERE u.id > :afterId
              AND u.username LIKE :pattern
              AND u.password_hash = :hash
              AND u.is_bot = FALSE
              AND u.created_at < :cutoff
              AND NOT EXISTS (SELECT 1 FROM user_game_stats s WHERE s.user_id = u.id)
              AND NOT EXISTS (SELECT 1 FROM chat_reports r
                              WHERE r.reported_user_id = u.id OR r.reporter_user_id = u.id)
              AND NOT EXISTS (SELECT 1 FROM admin_roles a WHERE a.user_id = u.id)
            ORDER BY u.id
            LIMIT :batch
            """, nativeQuery = true)
    List<Long> findExpiredGuestIds(@Param("afterId") long afterId,
                                   @Param("cutoff") LocalDateTime cutoff,
                                   @Param("pattern") String pattern,
                                   @Param("hash") String hash,
                                   @Param("batch") int batch);

    /**
     * D-117 — 게스트 행 1개 삭제. 조회와 삭제 사이에 상태가 바뀌었을 수 있으므로 게스트 규약과
     * "끝낸 매치 없음"을 다시 단언한다(정회원·봇 행은 이 문장으로 절대 지워지지 않는다).
     * user_avatars·user_game_stats 는 ON DELETE CASCADE.
     */
    @Modifying
    @Query(value = """
            DELETE FROM users u
            WHERE u.id = :id
              AND u.username LIKE :pattern
              AND u.password_hash = :hash
              AND u.is_bot = FALSE
              AND NOT EXISTS (SELECT 1 FROM user_game_stats s WHERE s.user_id = u.id)
            """, nativeQuery = true)
    int deleteGuestById(@Param("id") long id,
                        @Param("pattern") String pattern,
                        @Param("hash") String hash);

    // D-115 — 랭킹·승패·탈주·ELO 쓰기는 게임별 user_game_stats 로 옮겨졌다
    // (UserGameStatsService). users 의 옛 컬럼은 이관 후 읽지도 쓰지도 않으며, 별도
    // 마이그레이션으로 DROP 한다 — 그 전까지 다시 쓰는 경로가 생기지 않게 메서드를 없앤다.
}
