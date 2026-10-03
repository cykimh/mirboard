package com.mirboard.domain.lobby.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Phase 9A — 시드 봇 4명 조회. id 오름차순으로 안정 정렬. */
    @Query("SELECT u FROM User u WHERE u.isBot = true ORDER BY u.id ASC")
    List<User> findBots();

    // D-115 — 랭킹·승패·탈주·ELO 쓰기는 게임별 user_game_stats 로 옮겨졌다
    // (UserGameStatsService). users 의 옛 컬럼은 이관 후 읽지도 쓰지도 않으며, 별도
    // 마이그레이션으로 DROP 한다 — 그 전까지 다시 쓰는 경로가 생기지 않게 메서드를 없앤다.
}
