package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-300 — V23 이 사람 계정의 한 판 이상 한 전적 행만 +40 올리고, 올리기 전 값을 백업하며, 다시 돌려도 두 번 올리지 않는지.
 *
 * <p>{@link V11UserGameStatsMigrationIT} 와 같은 이유로 운영 순서를 재현한다: V22 까지 → 데이터 삽입 → V23. 운영 DB 는
 * {@code mirboard} 스키마(D-169)라 기본 스키마가 public 이 아닐 때도 같은지 본다(스크립트에 스키마 이름이 없어야 한다).
 */
@Testcontainers
class V23RatingRaiseMigrationIT {

    private static final String SCRIPT = "db/migration/V23__rating_raise_d300.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Flyway flywayUpTo(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void raises_played_human_rows_once_in_the_public_schema() throws Exception {
        verify("public");
    }

    @Test
    void follows_the_default_schema_when_it_is_not_public() throws Exception {
        verify("mirboard");
    }

    private void verify(String schema) throws Exception {
        flywayUpTo(schema, "22").migrate();
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + schema);
            st.executeUpdate("INSERT INTO users (username, password_hash) VALUES"
                    + " ('winner', 'x'), ('loser', 'x'), ('drawer', 'x'), ('idle', 'x'), ('guest-abc123', 'x')");
            st.executeUpdate("INSERT INTO user_game_stats (user_id, game_type, rating, win_count, lose_count)"
                    + " SELECT id, 'ONECARD', 1100, 3, 1 FROM users WHERE username = 'winner'"
                    + " UNION ALL SELECT id, 'YACHT', 1000, 0, 0 FROM users WHERE username = 'winner'"   // 0판 행
                    + " UNION ALL SELECT id, 'ONECARD', 950, 0, 4 FROM users WHERE username = 'loser'"
                    + " UNION ALL SELECT id, 'OMOK', 1000, 0, 0 FROM users WHERE username = 'drawer'"    // 무승부만
                    + " UNION ALL SELECT id, 'OMOK', 1000, 0, 0 FROM users WHERE username = 'idle'"      // 0판 행
                    + " UNION ALL SELECT id, 'ONECARD', 1000, 2, 2 FROM users WHERE username = 'guest-abc123'"
                    + " UNION ALL SELECT id, 'ONECARD', 1000, 9, 0 FROM users WHERE username = 'bot_north'");
            st.executeUpdate("INSERT INTO user_monthly_stats (user_id, game_type, stat_month, recent)"
                    + " SELECT id, 'OMOK', DATE '2026-11-01', 'D' FROM users WHERE username = 'drawer'");

            flywayUpTo(schema, "23").migrate();

            Map<String, Integer> expected = new TreeMap<>(Map.of(
                    "winner:ONECARD", 1140,
                    "winner:YACHT", 1000,
                    "loser:ONECARD", 990,
                    "drawer:OMOK", 1040,
                    "idle:OMOK", 1000,
                    "guest-abc123:ONECARD", 1000,
                    "bot_north:ONECARD", 1000));
            assertThat(column(st, "user_game_stats", "rating")).isEqualTo(expected);

            // 백업은 모든 행의 올리기 전 값 + 올린 점수.
            assertThat(column(st, "user_game_stats_backup_d300", "rating")).isEqualTo(new TreeMap<>(Map.of(
                    "winner:ONECARD", 1100,
                    "winner:YACHT", 1000,
                    "loser:ONECARD", 950,
                    "drawer:OMOK", 1000,
                    "idle:OMOK", 1000,
                    "guest-abc123:ONECARD", 1000,
                    "bot_north:ONECARD", 1000)));
            assertThat(column(st, "user_game_stats_backup_d300", "raised_by")).isEqualTo(new TreeMap<>(Map.of(
                    "winner:ONECARD", 40,
                    "winner:YACHT", 0,
                    "loser:ONECARD", 40,
                    "drawer:OMOK", 40,
                    "idle:OMOK", 0,
                    "guest-abc123:ONECARD", 0,
                    "bot_north:ONECARD", 0)));
            // 이번 달 전적은 그대로.
            try (ResultSet rs = st.executeQuery("SELECT count(*), max(recent) FROM user_monthly_stats")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.getString(2)).isEqualTo("D");
            }

            // 스크립트를 손으로 다시 돌려도 두 번 오르지 않는다 — 그 사이 판으로 바뀐 레이팅도 지킨다.
            st.executeUpdate("UPDATE user_game_stats SET rating = 1156 WHERE game_type = 'ONECARD' AND rating = 1140");
            ScriptUtils.executeSqlScript(c, new ClassPathResource(SCRIPT));
            expected.put("winner:ONECARD", 1156);
            assertThat(column(st, "user_game_stats", "rating")).isEqualTo(expected);

            // 되돌리기 (가) — 올린 만큼만 뺀다. 두 번 해도 한 번만 빠진다.
            for (int i = 0; i < 2; i++) {
                st.executeUpdate("UPDATE user_game_stats s SET rating = s.rating - b.raised_by"
                        + " FROM user_game_stats_backup_d300 b"
                        + " WHERE b.user_id = s.user_id AND b.game_type = s.game_type AND b.raised_by > 0");
                st.executeUpdate("UPDATE user_game_stats_backup_d300 SET raised_by = 0 WHERE raised_by > 0");
            }
            expected.put("winner:ONECARD", 1116);
            expected.put("loser:ONECARD", 950);
            expected.put("drawer:OMOK", 1000);
            assertThat(column(st, "user_game_stats", "rating")).isEqualTo(expected);
        }
    }

    private static Map<String, Integer> column(Statement st, String table, String column) throws Exception {
        Map<String, Integer> rows = new TreeMap<>();
        try (ResultSet rs = st.executeQuery("SELECT u.username, s.game_type, s." + column
                + " FROM " + table + " s JOIN users u ON u.id = s.user_id")) {
            while (rs.next()) {
                rows.put(rs.getString(1) + ":" + rs.getString(2), rs.getInt(3));
            }
        }
        return rows;
    }
}
