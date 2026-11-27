package com.mirboard.domain.game.wordtemp.feedback;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link TempFeedbackStore} 의 Postgres 구현(V26 {@code word_temp_feedback}). */
@Repository
public class JdbcTempFeedbackStore implements TempFeedbackStore {

    /** 어드민 목록 상한. */
    static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcTempFeedbackStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void record(String answer, String hint, String direction, int temp, String version) {
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("INSERT INTO word_temp_feedback (answer, hint, direction, temp, table_version, count, first_seen, last_seen)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?)"
                        + " ON CONFLICT (answer, hint, direction, table_version)"
                        + " DO UPDATE SET count = word_temp_feedback.count + 1, temp = EXCLUDED.temp,"
                        + " last_seen = EXCLUDED.last_seen",
                answer, hint, direction, temp, version, now, now);
    }

    @Override
    public List<Row> top(int limit) {
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        return jdbc.query("SELECT answer, hint, direction, temp, table_version, count, first_seen, last_seen"
                        + " FROM word_temp_feedback ORDER BY count DESC, last_seen DESC, answer, hint LIMIT ?",
                (rs, i) -> new Row(rs.getString("answer"), rs.getString("hint"), rs.getString("direction"),
                        rs.getInt("temp"), rs.getString("table_version"), rs.getInt("count"),
                        rs.getTimestamp("first_seen").toInstant(), rs.getTimestamp("last_seen").toInstant()),
                n);
    }
}
