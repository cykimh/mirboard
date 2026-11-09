package com.mirboard.domain.game.omok.persistence;

import com.mirboard.domain.game.core.HeadToHeadSource;
import com.mirboard.domain.game.omok.OmokGameDefinition;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 오목 상대 전적 — 같은 매치의 <b>상대 편</b>(다른 팀)만 센다(2:2 의 팀원은 상대가 아니다). 승·패·무. */
@Component
public class OmokHeadToHead implements HeadToHeadSource {

    private final JdbcTemplate jdbc;

    public OmokHeadToHead(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String gameType() {
        return OmokGameDefinition.ID;
    }

    @Override
    public List<Row> against(long userId) {
        return jdbc.query("""
                SELECT o.user_id AS opp,
                       SUM(CASE WHEN me.is_win THEN 1 ELSE 0 END) AS w,
                       SUM(CASE WHEN o.is_win THEN 1 ELSE 0 END) AS l,
                       SUM(CASE WHEN me.is_draw THEN 1 ELSE 0 END) AS d
                  FROM omok_match_participants me
                  JOIN omok_match_participants o ON o.match_id = me.match_id AND o.team <> me.team
                 WHERE me.user_id = ?
                 GROUP BY o.user_id
                """, (rs, i) -> new Row(rs.getLong("opp"), rs.getInt("w"), rs.getInt("l"), rs.getInt("d")), userId);
    }
}
