package com.mirboard.domain.game.artillery.persistence;

import com.mirboard.domain.game.core.HeadToHeadSource;
import com.mirboard.domain.game.artillery.ArtilleryGameDefinition;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 씨앗포 상대 전적: 같은 매치의 다른 사람과 최종 순위를 견준다(앞서면 승, 같으면 무 — 1, 1, 3 식 동순위). */
@Component
public class ArtilleryHeadToHead implements HeadToHeadSource {

    private final JdbcTemplate jdbc;

    public ArtilleryHeadToHead(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String gameType() {
        return ArtilleryGameDefinition.ID;
    }

    @Override
    public List<Row> against(long userId) {
        return jdbc.query("""
                SELECT o.user_id AS opp,
                       SUM(CASE WHEN me.final_rank < o.final_rank THEN 1 ELSE 0 END) AS w,
                       SUM(CASE WHEN me.final_rank > o.final_rank THEN 1 ELSE 0 END) AS l,
                       SUM(CASE WHEN me.final_rank = o.final_rank THEN 1 ELSE 0 END) AS d
                  FROM artillery_match_participants me
                  JOIN artillery_match_participants o ON o.match_id = me.match_id AND o.user_id <> me.user_id
                 WHERE me.user_id = ?
                 GROUP BY o.user_id
                """, (rs, i) -> new Row(rs.getLong("opp"), rs.getInt("w"), rs.getInt("l"), rs.getInt("d")), userId);
    }
}
