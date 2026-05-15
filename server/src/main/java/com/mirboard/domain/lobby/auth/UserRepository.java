package com.mirboard.domain.lobby.auth;

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.winCount = u.winCount + 1 WHERE u.id = :id")
    int incrementWinCount(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.loseCount = u.loseCount + 1 WHERE u.id = :id")
    int incrementLoseCount(@Param("id") long id);

    /** Phase 8D — ELO 갱신. EloCalculator 가 계산한 newRating 으로 덮어쓰기. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.rating = :rating WHERE u.id = :id")
    int updateRating(@Param("id") long id, @Param("rating") int rating);
}
