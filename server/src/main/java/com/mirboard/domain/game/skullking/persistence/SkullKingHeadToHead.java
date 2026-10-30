package com.mirboard.domain.game.skullking.persistence;

import com.mirboard.domain.game.core.HeadToHeadSource;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** D-154 — 스컬킹 상대 전적: 같은 매치의 다른 사람과 최종 점수를 견준다(높으면 승, 같으면 무). */
@Component
public class SkullKingHeadToHead implements HeadToHeadSource {

    private final JdbcTemplate jdbc;

    public SkullKingHeadToHead(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String gameType() {
        return "SKULL_KING";
    }

    @Override
    public List<Row> against(long userId) {
        return jdbc.query("""
                SELECT o.user_id AS opp,
                       SUM(CASE WHEN me.final_score > o.final_score THEN 1 ELSE 0 END) AS w,
                       SUM(CASE WHEN me.final_score < o.final_score THEN 1 ELSE 0 END) AS l,
                       SUM(CASE WHEN me.final_score = o.final_score THEN 1 ELSE 0 END) AS d
                  FROM skullking_match_participants me
                  JOIN skullking_match_participants o ON o.match_id = me.match_id AND o.user_id <> me.user_id
                 WHERE me.user_id = ?
                 GROUP BY o.user_id
                """, (rs, i) -> new Row(rs.getLong("opp"), rs.getInt("w"), rs.getInt("l"), rs.getInt("d")), userId);
    }
}
