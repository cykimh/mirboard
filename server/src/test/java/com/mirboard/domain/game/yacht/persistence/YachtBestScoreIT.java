package com.mirboard.domain.game.yacht.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirboard.domain.game.scoring.BestScoreService;
import com.mirboard.domain.game.yacht.YachtGameDefinition;
import com.mirboard.domain.game.yacht.event.YachtMatchCompleted;
import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.MatchResult.Placement;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * D-201 — 요트 개인 최고 점수. 1인 판은 끝까지 친 판만 {@code yacht_solo_scores} 에 남고(게스트·봇 제외), 최고 = 1인 판과 끝까지 친
 * 여럿 판 중 최고이며, {@code GET /api/users/{id}/stats} 의 {@code games[].bestScore} 로 나간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=yacht-best-test-secret-must-be-32-bytes-or-more"
})
class YachtBestScoreIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired YachtMatchRecorder recorder;
    @Autowired BestScoreService bestScores;
    @Autowired UserRepository users;
    @Autowired BotUserRegistry bots;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwt;

    private static final String GAME = YachtGameDefinition.ID;

    @Test
    void a_finished_solo_match_is_kept_and_becomes_the_best_score() throws Exception {
        User alice = newUser("ytbest");
        String first = solo(alice.getId(), EndReason.FINISHED, 180);
        solo(alice.getId(), EndReason.FINISHED, 240);
        solo(alice.getId(), EndReason.ACTION_LIMIT, 300); // 끝까지 안 친 판은 남지 않는다

        assertThat(count(alice.getId())).isEqualTo(2);
        assertThat(bestScores.bestScores(alice)).containsExactly(Map.entry(GAME, 240));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yacht_match_results WHERE room_id = ?",
                Integer.class, first)).as("1인 판은 매치 기록이 없다(D-181)").isZero();

        // 혼자만 한 게임도 0승 0패 줄로 bestScore 가 실린다.
        mockMvc.perform(get("/api/users/" + alice.getId() + "/stats")
                        .header("Authorization", "Bearer " + jwt.issue(alice.getId(), alice.getUsername()).token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games[?(@.gameType == 'YACHT')].bestScore").value(240))
                .andExpect(jsonPath("$.games[?(@.gameType == 'YACHT')].winCount").value(0));
    }

    @Test
    void a_finished_multiplayer_score_counts_but_a_deserted_or_unfinished_one_does_not() {
        User bob = newUser("ytmulti");
        User carol = newUser("ytmulti");
        solo(bob.getId(), EndReason.FINISHED, 150);
        multi(List.of(bob.getId(), carol.getId()), EndReason.FINISHED,
                List.of(new Placement(0, 1, 260, false), new Placement(1, 2, 200, false)));
        multi(List.of(carol.getId(), bob.getId()), EndReason.FINISHED,
                List.of(new Placement(0, 1, 310, false), new Placement(1, 2, 290, true))); // bob 탈주
        multi(List.of(bob.getId(), carol.getId()), EndReason.NO_HUMANS,
                List.of(new Placement(0, 1, 330, false), new Placement(1, 2, 100, true)));

        assertThat(bestScores.bestScores(bob)).containsExactly(Map.entry(GAME, 260));
        assertThat(bestScores.bestScores(carol)).containsExactly(Map.entry(GAME, 310));
    }

    @Test
    void guests_and_bots_get_nothing() {
        User guest = users.save(User.create(GuestPolicy.PREFIX + UUID.randomUUID().toString().substring(0, 8),
                GuestPolicy.NO_LOGIN_HASH, Clock.systemUTC()));
        solo(guest.getId(), EndReason.FINISHED, 200);
        assertThat(count(guest.getId())).as("게스트 1인 판은 남기지 않는다").isZero();

        long botId = bots.getBotIds().get(0);
        solo(botId, EndReason.FINISHED, 200);
        assertThat(count(botId)).isZero();

        // 여럿 판 참가자 표에는 게스트 행이 있어도 최고 점수로는 보이지 않는다.
        User dave = newUser("ytguest");
        multi(List.of(dave.getId(), guest.getId()), EndReason.FINISHED,
                List.of(new Placement(1, 1, 250, false), new Placement(0, 2, 220, false)));
        assertThat(bestScores.bestScores(guest)).isEmpty();
        assertThat(bestScores.bestScores(users.findById(botId).orElseThrow())).isEmpty();
        assertThat(bestScores.bestScores(dave)).containsExactly(Map.entry(GAME, 220));
    }

    @Test
    void a_user_without_any_yacht_game_has_no_best_score() {
        assertThat(bestScores.bestScores(newUser("ytnone"))).isEmpty();
    }

    private String solo(long userId, EndReason reason, int total) {
        String roomId = UUID.randomUUID().toString();
        recorder.onMatchCompleted(new YachtMatchCompleted(roomId, List.of(userId),
                new MatchResult(reason, List.of(new Placement(0, 1, total, false)))));
        return roomId;
    }

    private void multi(List<Long> playerIds, EndReason reason, List<Placement> placements) {
        recorder.onMatchCompleted(new YachtMatchCompleted(UUID.randomUUID().toString(), playerIds,
                new MatchResult(reason, placements)));
    }

    private int count(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM yacht_solo_scores WHERE user_id = ?", Integer.class, userId);
    }

    private User newUser(String prefix) {
        return users.save(User.create(prefix + UUID.randomUUID().toString().substring(0, 4), "x", Clock.systemUTC()));
    }
}
