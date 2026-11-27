package com.mirboard.infra.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirboard.domain.admin.AdminRole;
import com.mirboard.domain.admin.AdminRoleRepository;
import com.mirboard.domain.game.core.GameFeedbackSource;
import com.mirboard.domain.game.wordtemp.feedback.TempFeedbackStore;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-323 — 게임이 모은 신고 조회(게임 중립 {@code GET /api/admin/games/{gameType}/feedback}) + 권한 게이트. 풀 컨텍스트라 Flyway V26
 * 적용과 신고 표의 "같은 쌍은 횟수만 올린다"도 여기서 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=admin-feedback-test-secret-must-be-32-bytes-or-more"
})
class AdminGameFeedbackIT {

    private static final String GAME = "WORD_TEMP";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired AdminRoleRepository adminRoleRepo;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<GameFeedbackSource> sources;
    @Autowired TempFeedbackStore store;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        return userRepo.save(User.create(prefix + SEQ.incrementAndGet(),
                passwordEncoder.encode("validpass1"), clock));
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.issue(u.getId(), u.getUsername()).token();
    }

    private void flag(String answer, String hint, String direction, int temp, String version) {
        store.record(answer, hint, direction, temp, version);
    }

    @Test
    void admin_reads_the_most_flagged_pairs_first_and_rows_hold_words_only() throws Exception {
        jdbc.update("DELETE FROM word_temp_feedback");
        flag("바다", "여름", "LOW", 58, "v1");
        flag("바다", "여름", "LOW", 58, "v1");
        flag("바다", "여름", "LOW", 60, "v1"); // 같은 쌍 — 횟수만 오르고 온도는 마지막 값
        flag("바다", "소금", "HIGH", 71, "live");
        User admin = newUser("fbadmin");
        adminRoleRepo.save(new AdminRole(admin.getId(), clock.instant()));

        mockMvc.perform(get("/api/admin/games/" + GAME + "/feedback").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameType").value(GAME))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].answer").value("바다"))
                .andExpect(jsonPath("$.items[0].hint").value("여름"))
                .andExpect(jsonPath("$.items[0].direction").value("LOW"))
                .andExpect(jsonPath("$.items[0].temp").value(60))
                .andExpect(jsonPath("$.items[0].version").value("v1"))
                .andExpect(jsonPath("$.items[0].count").value(3))
                .andExpect(jsonPath("$.items[1].hint").value("소금"))
                .andExpect(jsonPath("$.items[1].count").value(1));
        mockMvc.perform(get("/api/admin/games/" + GAME + "/feedback?limit=1").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.items.length()").value(1));

        // 표에는 사람·방·좌석을 가리키는 열이 없다
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'word_temp_feedback'", String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "answer", "hint", "direction", "temp", "table_version", "count", "first_seen", "last_seen");
        // 포트가 내주는 줄도 같은 것만
        Map<String, Object> row = sources.stream().filter(s -> s.gameType().equals(GAME)).findFirst().orElseThrow()
                .top(10).getFirst();
        assertThat(row.keySet()).containsExactlyInAnyOrder(
                "answer", "hint", "direction", "temp", "version", "count", "firstSeen", "lastSeen");
    }

    @Test
    void non_admin_is_refused_and_a_game_without_feedback_reads_empty() throws Exception {
        User user = newUser("fbuser");
        mockMvc.perform(get("/api/admin/games/" + GAME + "/feedback").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        mockMvc.perform(get("/api/admin/games/" + GAME + "/feedback"))
                .andExpect(status().isUnauthorized());

        User admin = newUser("fbadmin");
        adminRoleRepo.save(new AdminRole(admin.getId(), clock.instant()));
        mockMvc.perform(get("/api/admin/games/TICHU/feedback").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
