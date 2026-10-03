package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-115 — V11 이 기존 티츄 전적을 {@code user_game_stats} 의 TICHU 행으로 옮기는지.
 *
 * <p>다른 IT 는 빈 DB 에 V1~최신을 한 번에 올리므로 이관 SQL 이 실제 데이터를 만나는 일이
 * 없다. 운영 DB 는 V10 상태에 사람 데이터가 있는 채로 V11 을 맞으므로, 그 순서를 그대로
 * 재현한다: V10 까지 → 데이터 삽입 → V11.
 */
@Testcontainers
class V11UserGameStatsMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Flyway flywayUpTo(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void active_humans_get_a_tichu_row_and_idle_humans_and_bots_do_not() throws Exception {
        flywayUpTo("10").migrate();
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO users (username, password_hash, win_count, lose_count, rating, desert_count)"
                    + " VALUES ('veteran', 'x', 7, 3, 1088, 1), ('fresh', 'x', 0, 0, 1000, 0)");
            st.executeUpdate("UPDATE users SET win_count = 5 WHERE username = 'bot_north'");

            flywayUpTo("11").migrate();

            Map<String, String> rows = new HashMap<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT u.username, s.game_type, s.rating, s.win_count, s.lose_count, s.desert_count"
                            + " FROM user_game_stats s JOIN users u ON u.id = s.user_id")) {
                while (rs.next()) {
                    rows.put(rs.getString(1), rs.getString(2) + ":" + rs.getInt(3) + ":"
                            + rs.getInt(4) + ":" + rs.getInt(5) + ":" + rs.getInt(6));
                }
            }
            assertThat(rows).containsExactly(Map.entry("veteran", "TICHU:1088:7:3:1"));
        }
    }
}
