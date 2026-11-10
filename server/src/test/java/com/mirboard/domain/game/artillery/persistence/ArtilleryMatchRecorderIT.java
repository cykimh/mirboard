package com.mirboard.domain.game.artillery.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.artillery.ArtilleryGameDefinition;
import com.mirboard.domain.game.artillery.event.ArtilleryMatchCompleted;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.MatchResult.EndReason;
import com.mirboard.domain.game.artillery.state.MatchResult.Placement;
import com.mirboard.domain.game.core.HeadToHeadSource;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** D-222·D-225 — 씨앗포 매치 종료가 매치 기록(V16) + 게임별 전적(승패·개인전 ELO·탈주) + 상대 전적으로 남는지. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=ar-recorder-test-secret-must-be-32-bytes-or-more"
})
class ArtilleryMatchRecorderIT {

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

    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserGameStatsService stats;
    @Autowired UserRepository users;
    @Autowired BotUserRegistry bots;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArtilleryHeadToHead headToHead;

    private long human() {
        String name = "ar" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats ar(long userId) {
        return stats.get(userId, ArtilleryGameDefinition.ID);
    }

    @Test
    void a_human_match_records_rows_win_lose_free_for_all_elo_and_head_to_head() {
        long a = human();
        long b = human();
        long c = human();
        String room = UUID.randomUUID().toString();

        publisher.publishEvent(new ArtilleryMatchCompleted(room, List.of(a, b, c), new MatchResult(EndReason.LAST_STANDING,
                List.of(new Placement(1, 1, 64, false), new Placement(0, 2, 0, false), new Placement(2, 3, 30, true)))));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM artillery_match_results WHERE room_id = ? AND end_reason = 'LAST_STANDING'",
                Integer.class, room)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT p.final_rank, p.final_hp, p.is_win, p.deserted FROM artillery_match_participants p"
                        + " JOIN artillery_match_results r ON r.id = p.match_id WHERE r.room_id = ? ORDER BY p.seat", room))
                .extracting(r -> r.get("final_rank") + "/" + r.get("final_hp") + "/" + r.get("is_win") + "/" + r.get("deserted"))
                .containsExactly("2/0/false/false", "1/64/true/false", "3/30/false/true");
        assertThat(ar(b).rating()).isEqualTo(1020);
        assertThat(ar(a).rating()).isEqualTo(1000);
        assertThat(ar(c).rating()).isEqualTo(980);
        assertThat(ar(b).winCount()).isEqualTo(1);
        assertThat(ar(c).desertCount()).isEqualTo(1);
        assertThat(headToHead.against(b)).extracting(HeadToHeadSource.Row::opponentId).containsExactlyInAnyOrder(a, c);
    }

    /** D-225 — 2:2 팀전: 팀 평균 ELO, 이긴 팀의 탈주자는 혼자 진 것으로, 참가자 행에 팀(V18), 상대 전적은 상대 팀만. */
    @Test
    void a_team_match_records_team_elo_team_column_and_head_to_head_only_against_the_other_team() {
        long a = human();
        long b = human();
        long c = human();
        long d = human();
        String room = UUID.randomUUID().toString();
        // 좌석 0·2 = 팀 0(이김, 좌석 2 는 탈주), 좌석 1·3 = 팀 1.
        publisher.publishEvent(new ArtilleryMatchCompleted(room, List.of(a, b, c, d), new MatchResult(
                EndReason.LAST_STANDING, List.of(new Placement(0, 1, 40, false), new Placement(1, 2, 0, false),
                new Placement(3, 2, 0, false), new Placement(2, 4, 70, true)), true, 0)));

        assertThat(jdbc.queryForList(
                "SELECT p.team, p.final_rank, p.is_win, p.deserted FROM artillery_match_participants p"
                        + " JOIN artillery_match_results r ON r.id = p.match_id WHERE r.room_id = ? ORDER BY p.seat", room))
                .extracting(r -> r.get("team") + "/" + r.get("final_rank") + "/" + r.get("is_win") + "/" + r.get("deserted"))
                .containsExactly("0/1/true/false", "1/2/false/false", "0/4/false/true", "1/2/false/false");
        assertThat(ar(a).rating()).isEqualTo(1020);
        assertThat(ar(b).rating()).isEqualTo(980);
        assertThat(ar(d).rating()).isEqualTo(980);
        assertThat(ar(c).rating()).isEqualTo(980);   // 이긴 팀이지만 탈주 — 상대 팀에 혼자 졌다
        assertThat(ar(c).desertCount()).isEqualTo(1);
        assertThat(ar(c).winCount()).isZero();
        assertThat(ar(a).winCount()).isEqualTo(1);
        // 같은 팀원(a↔c, b↔d)은 상대 전적에 없다.
        assertThat(headToHead.against(a)).extracting(HeadToHeadSource.Row::opponentId).containsExactlyInAnyOrder(b, d);
        assertThat(headToHead.against(b)).extracting(HeadToHeadSource.Row::opponentId).containsExactlyInAnyOrder(a, c);
    }

    /** D-225 — 팀전 무승부: ELO·승패 변화 없이 행만, 상대 전적은 무승부. */
    @Test
    void a_team_draw_changes_no_rating_and_counts_as_a_draw() {
        long a = human();
        long b = human();
        long c = human();
        long d = human();
        String room = UUID.randomUUID().toString();
        publisher.publishEvent(new ArtilleryMatchCompleted(room, List.of(a, b, c, d), new MatchResult(
                EndReason.ALL_DOWN, List.of(new Placement(0, 1, 0, false), new Placement(1, 1, 0, false),
                new Placement(2, 1, 0, false), new Placement(3, 1, 0, false)), true, null)));
        for (long u : List.of(a, b, c, d)) {
            assertThat(ar(u).rating()).isEqualTo(UserGameStatsService.DEFAULT_RATING);
            assertThat(ar(u).winCount()).isZero();
            assertThat(ar(u).loseCount()).isZero();
        }
        assertThat(headToHead.against(a)).allMatch(r -> r.draws() == 1 && r.wins() == 0 && r.losses() == 0);
    }

    @Test
    void a_match_with_a_bot_records_win_lose_but_no_elo() {
        long a = human();
        long bot = bots.getBotIds().get(0);
        String room = UUID.randomUUID().toString();
        publisher.publishEvent(new ArtilleryMatchCompleted(room, List.of(a, bot), new MatchResult(EndReason.NO_HUMANS,
                List.of(new Placement(1, 1, 80, false), new Placement(0, 2, 0, false)))));
        assertThat(ar(a).loseCount()).isEqualTo(1);
        assertThat(ar(a).rating()).isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }
}
