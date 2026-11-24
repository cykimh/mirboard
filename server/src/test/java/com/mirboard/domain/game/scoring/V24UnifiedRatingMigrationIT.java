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
 * D-305 — V24 가 사람마다 통합 레이팅 행('*')을 판 수 가중 평균으로 만들고(봇·게스트 제외), 게임별 레이팅을 백업하며, 다시 돌려도
 * 그 뒤 판으로 움직인 통합 레이팅을 덮지 않는지.
 *
 * <p>{@link V23RatingRaiseMigrationIT} 와 같이 운영 순서를 재현한다: V23 까지 → 데이터 삽입 → V24. 운영 DB 는 {@code mirboard}
 * 스키마(D-169)라 기본 스키마가 public 이 아닐 때도 같은지 본다.
 */
@Testcontainers
class V24UnifiedRatingMigrationIT {

    private static final String SCRIPT = "db/migration/V24__unified_rating_d305.sql";

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
    void builds_the_unified_row_in_the_public_schema() throws Exception {
        verify("public");
    }

    @Test
    void follows_the_default_schema_when_it_is_not_public() throws Exception {
        verify("mirboard");
    }

    private void verify(String schema) throws Exception {
        flywayUpTo(schema, "23").migrate();
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + schema);
            st.executeUpdate("INSERT INTO users (username, password_hash) VALUES"
                    + " ('multi', 'x'), ('single', 'x'), ('drawer', 'x'), ('fresh', 'x'), ('guest-abc123', 'x')");
            st.executeUpdate("INSERT INTO user_game_stats"
                    + " (user_id, game_type, rating, win_count, lose_count, desert_count)"
                    // multi: (1200×10 + 1000×5 + 1100×0) / 15 = 1133.33 → 1133. 0판 행(YACHT)은 무게 0.
                    + " SELECT id, 'ONECARD', 1200, 6, 4, 1 FROM users WHERE username = 'multi'"
                    + " UNION ALL SELECT id, 'TICHU', 1000, 2, 3, 0 FROM users WHERE username = 'multi'"
                    + " UNION ALL SELECT id, 'YACHT', 1100, 0, 0, 0 FROM users WHERE username = 'multi'"
                    + " UNION ALL SELECT id, 'ONECARD', 957, 1, 6, 2 FROM users WHERE username = 'single'"
                    // drawer: 승·패 0 — 가진 행의 단순 평균 (1046 + 1041) / 2 = 1043.5 → 1044.
                    + " UNION ALL SELECT id, 'OMOK', 1046, 0, 0, 0 FROM users WHERE username = 'drawer'"
                    + " UNION ALL SELECT id, 'ARTILLERY', 1041, 0, 0, 0 FROM users WHERE username = 'drawer'"
                    + " UNION ALL SELECT id, 'ONECARD', 1000, 2, 2, 0 FROM users WHERE username = 'guest-abc123'"
                    + " UNION ALL SELECT id, 'ONECARD', 1000, 9, 0, 0 FROM users WHERE username = 'bot_north'");

            flywayUpTo(schema, "24").migrate();

            // 통합 행: 사람만. 전적 행이 없는 fresh·게스트·봇은 만들지 않는다(앱이 1000 으로 읽는다).
            Map<String, Integer> unified = new TreeMap<>(Map.of("multi", 1133, "single", 957, "drawer", 1044));
            assertThat(unifiedColumn(st, "rating")).isEqualTo(unified);
            assertThat(unifiedColumn(st, "win_count")).isEqualTo(Map.of("multi", 8, "single", 1, "drawer", 0));
            assertThat(unifiedColumn(st, "lose_count")).isEqualTo(Map.of("multi", 7, "single", 6, "drawer", 0));
            assertThat(unifiedColumn(st, "desert_count")).isEqualTo(Map.of("multi", 1, "single", 2, "drawer", 0));

            // 게임별 레이팅은 그대로이고, 백업에는 게임별 값 전부 + 만든 통합 시작값.
            Map<String, Integer> games = new TreeMap<>(Map.of(
                    "multi:ONECARD", 1200, "multi:TICHU", 1000, "multi:YACHT", 1100,
                    "single:ONECARD", 957, "drawer:OMOK", 1046, "drawer:ARTILLERY", 1041,
                    "guest-abc123:ONECARD", 1000, "bot_north:ONECARD", 1000));
            assertThat(rows(st, "user_game_stats", "s.game_type <> '*'")).isEqualTo(games);
            Map<String, Integer> backup = new TreeMap<>(games);
            backup.put("multi:*", 1133);
            backup.put("single:*", 957);
            backup.put("drawer:*", 1044);
            assertThat(rows(st, "user_game_stats_backup_d305", "TRUE")).isEqualTo(backup);

            // 그 뒤 판으로 통합·게임별 레이팅이 움직이고 새 사람이 생긴 뒤 스크립트를 손으로 다시 돌려도:
            // 이미 있는 통합 레이팅은 덮지 않고, 백업도 처음 값 그대로, 새 사람만 통합 행을 얻는다.
            st.executeUpdate("UPDATE user_game_stats SET rating = 1159 WHERE rating = 1133 AND game_type = '*'");
            st.executeUpdate("UPDATE user_game_stats SET rating = 1226 WHERE rating = 1200 AND game_type = 'ONECARD'");
            st.executeUpdate("INSERT INTO user_game_stats (user_id, game_type, rating, win_count, lose_count)"
                    + " SELECT id, 'OMOK', 1026, 1, 0 FROM users WHERE username = 'fresh'");
            ScriptUtils.executeSqlScript(c, new ClassPathResource(SCRIPT));
            unified.put("multi", 1159);
            unified.put("fresh", 1026);
            assertThat(unifiedColumn(st, "rating")).isEqualTo(unified);
            backup.put("fresh:OMOK", 1026);
            backup.put("fresh:*", 1026);
            assertThat(rows(st, "user_game_stats_backup_d305", "TRUE")).isEqualTo(backup);

            // 되돌리기 (나) — 통합 행을 지우고 게임별 레이팅을 백업 시점 값으로.
            st.executeUpdate("DELETE FROM user_game_stats WHERE game_type = '*'");
            st.executeUpdate("UPDATE user_game_stats s SET rating = b.rating FROM user_game_stats_backup_d305 b"
                    + " WHERE b.user_id = s.user_id AND b.game_type = s.game_type AND b.game_type <> '*'");
            games.put("fresh:OMOK", 1026);
            assertThat(rows(st, "user_game_stats", "TRUE")).isEqualTo(games);
        }
    }

    private static Map<String, Integer> unifiedColumn(Statement st, String column) throws Exception {
        Map<String, Integer> rows = new TreeMap<>();
        try (ResultSet rs = st.executeQuery("SELECT u.username, s." + column
                + " FROM user_game_stats s JOIN users u ON u.id = s.user_id WHERE s.game_type = '*'")) {
            while (rs.next()) {
                rows.put(rs.getString(1), rs.getInt(2));
            }
        }
        return rows;
    }

    private static Map<String, Integer> rows(Statement st, String table, String where) throws Exception {
        Map<String, Integer> rows = new TreeMap<>();
        try (ResultSet rs = st.executeQuery("SELECT u.username, s.game_type, s.rating"
                + " FROM " + table + " s JOIN users u ON u.id = s.user_id WHERE " + where)) {
            while (rs.next()) {
                rows.put(rs.getString(1) + ":" + rs.getString(2), rs.getInt(3));
            }
        }
        return rows;
    }
}
