package com.mirboard.domain.game.wordtemp.scoring;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link LiveScoreStore} 의 Postgres 구현(V25 {@code word_temp_live_scores}). */
@Repository
public class JdbcLiveScoreStore implements LiveScoreStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcLiveScoreStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<Stored> findAndTouch(Key key) {
        List<Stored> rows = jdbc.query("UPDATE word_temp_live_scores SET hits = hits + 1, last_seen_at = ?"
                        + " WHERE answer = ? AND hint = ? AND model = ? AND prompt_version = ? RETURNING valid, score",
                (rs, i) -> new Stored(rs.getBoolean("valid"), rs.getInt("score")),
                Timestamp.from(Instant.now(clock)), key.answer(), key.hint(), key.model(), key.promptVersion());
        return rows.stream().findFirst();
    }

    @Override
    public Stored insertFirst(Key key, Stored value) {
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("INSERT INTO word_temp_live_scores"
                        + " (answer, hint, model, prompt_version, valid, score, hits, created_at, last_seen_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?) ON CONFLICT DO NOTHING",
                key.answer(), key.hint(), key.model(), key.promptVersion(), value.valid(), value.score(), now, now);
        List<Stored> rows = jdbc.query("SELECT valid, score FROM word_temp_live_scores"
                        + " WHERE answer = ? AND hint = ? AND model = ? AND prompt_version = ?",
                (rs, i) -> new Stored(rs.getBoolean("valid"), rs.getInt("score")),
                key.answer(), key.hint(), key.model(), key.promptVersion());
        return rows.isEmpty() ? value : rows.get(0);
    }

    @Override
    public int createdSince(Instant since) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM word_temp_live_scores WHERE created_at >= ?",
                Integer.class, Timestamp.from(since));
        return count == null ? 0 : count;
    }
}
