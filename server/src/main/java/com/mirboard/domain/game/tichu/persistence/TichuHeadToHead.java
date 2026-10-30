package com.mirboard.domain.game.tichu.persistence;

import com.mirboard.domain.game.core.HeadToHeadSource;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** D-154 — 티츄 상대 전적: 상대 팀 사람만, 내 팀이 이겼으면 승(무승부 없음). 같은 팀은 상대가 아니다. */
@Component
public class TichuHeadToHead implements HeadToHeadSource {

    private final JdbcTemplate jdbc;

    public TichuHeadToHead(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String gameType() {
        return "TICHU";
    }

    @Override
    public List<Row> against(long userId) {
        return jdbc.query("""
                SELECT o.user_id AS opp,
                       SUM(CASE WHEN me.is_win THEN 1 ELSE 0 END) AS w,
                       SUM(CASE WHEN me.is_win THEN 0 ELSE 1 END) AS l
                  FROM tichu_match_participants me
                  JOIN tichu_match_participants o ON o.match_id = me.match_id AND o.team <> me.team
                 WHERE me.user_id = ?
                 GROUP BY o.user_id
                """, (rs, i) -> new Row(rs.getLong("opp"), rs.getInt("w"), rs.getInt("l"), 0), userId);
    }
}
