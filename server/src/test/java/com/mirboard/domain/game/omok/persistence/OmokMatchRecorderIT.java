package com.mirboard.domain.game.omok.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.HeadToHeadSource;
import com.mirboard.domain.game.omok.OmokEngine;
import com.mirboard.domain.game.omok.OmokGameDefinition;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.event.OmokMatchCompleted;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.MatchResult;
import com.mirboard.domain.game.omok.state.OmokState;
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

/** 오목 매치 종료가 매치 기록 + 게임별 전적(승패·팀 ELO·탈주·무승부)으로 남는지 (룰 §10.2). */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=omok-recorder-test-secret-must-be-32-bytes-or-more"
})
class OmokMatchRecorderIT {

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
    @Autowired List<HeadToHeadSource> headToHead;

    private static final OmokEngine ENGINE = new OmokEngine();

    private long human() {
        String name = "om" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats omok(long userId) {
        return stats.get(userId, OmokGameDefinition.ID);
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    /** 1:1, 흑(좌석 0)이 (3..7, 7) 로 이긴 판. */
    private static OmokState blackFiveSolo() {
        OmokState s = ENGINE.startMatch(OmokMode.SOLO, OmokRule.RENJU, 2, 0, 0);
        int[][] moves = {{3, 7}, {0, 0}, {4, 7}, {0, 2}, {5, 7}, {0, 4}, {6, 7}, {0, 6}, {7, 7}};
        for (int[] m : moves) {
            s = ENGINE.apply(s, s.turnSeat(), new OmokAction.Place(m[0], m[1])).newState();
        }
        return s;
    }

    private int statsRows(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_game_stats WHERE user_id = ? AND game_type = 'OMOK'",
                Integer.class, userId);
    }

    @Test
    void a_solo_human_match_records_rows_colors_and_elo() {
        long a = human();
        long b = human();
        String room = room();
        publisher.publishEvent(new OmokMatchCompleted(room, List.of(a, b), blackFiveSolo()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM omok_match_results WHERE room_id = ?"
                + " AND mode = 'SOLO' AND rule = 'RENJU' AND end_reason = 'FIVE' AND winner_color = 'BLACK'",
                Integer.class, room)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT p.seat, p.team, p.color, p.is_win, p.is_draw, p.deserted"
                        + " FROM omok_match_participants p JOIN omok_match_results r ON r.id = p.match_id"
                        + " WHERE r.room_id = ? ORDER BY p.seat", room))
                .extracting(r -> r.get("team") + "/" + r.get("color") + "/" + r.get("is_win") + "/" + r.get("is_draw")
                        + "/" + r.get("deserted"))
                .containsExactly("0/BLACK/true/false/false", "1/WHITE/false/false/false");
        assertThat(jdbc.queryForObject("SELECT payload_json FROM omok_match_results WHERE room_id = ?",
                String.class, room)).contains("\"moves\"");
        // 신규(K=40), 동일 레이팅 1:1: ±20.
        assertThat(omok(a).rating()).isEqualTo(1020);
        assertThat(omok(b).rating()).isEqualTo(980);
        assertThat(omok(a).winCount()).isEqualTo(1);
        assertThat(omok(b).loseCount()).isEqualTo(1);
    }

    @Test
    void a_team_resignation_loses_for_both_team_members_with_team_elo() {
        long a = human();
        long b = human();
        long c = human();
        long d = human();
        OmokState s = ENGINE.startMatch(OmokMode.TEAMS_2V2, OmokRule.FREESTYLE, 4, 0, 0);
        s = ENGINE.apply(s, 2, new OmokAction.Resign()).newState();      // 흑 팀(0·2) 기권
        publisher.publishEvent(new OmokMatchCompleted(room(), List.of(a, b, c, d), s));

        assertThat(omok(a).loseCount()).isEqualTo(1);
        assertThat(omok(c).loseCount()).isEqualTo(1);
        assertThat(omok(b).winCount()).isEqualTo(1);
        assertThat(omok(d).winCount()).isEqualTo(1);
        assertThat(omok(c).desertCount()).as("기권은 탈주가 아니다").isZero();
        assertThat(List.of(omok(a).rating(), omok(b).rating(), omok(c).rating(), omok(d).rating()))
                .containsExactly(980, 1020, 980, 1020);
    }

    @Test
    void a_draw_changes_no_counts_or_rating_but_leaves_a_stats_row() {
        long a = human();
        long b = human();
        OmokState base = blackFiveSolo();
        OmokState draw = new OmokState(OmokMode.SOLO, OmokRule.FREESTYLE, 0, 0, base.moves(), -1, null,
                new MatchResult(null, List.of(), EndReason.BOARD_FULL, -1, List.of()));
        String room = room();
        publisher.publishEvent(new OmokMatchCompleted(room, List.of(a, b), draw));

        assertThat(statsRows(a)).isEqualTo(1);
        assertThat(statsRows(b)).isEqualTo(1);
        assertThat(omok(a).winCount() + omok(a).loseCount() + omok(a).desertCount()).isZero();
        assertThat(omok(a).rating()).isEqualTo(UserGameStatsService.DEFAULT_RATING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM omok_match_participants p"
                        + " JOIN omok_match_results r ON r.id = p.match_id"
                        + " WHERE r.room_id = ? AND p.is_draw AND r.winner_color IS NULL", Integer.class, room))
                .isEqualTo(2);
    }

    @Test
    void the_deserter_gets_a_loss_and_a_desertion() {
        long a = human();
        long b = human();
        OmokState s = ENGINE.desert(ENGINE.startMatch(OmokMode.SOLO, OmokRule.RENJU, 2, 0, 0), 1).newState();
        publisher.publishEvent(new OmokMatchCompleted(room(), List.of(a, b), s));

        assertThat(omok(b).loseCount()).isEqualTo(1);
        assertThat(omok(b).desertCount()).isEqualTo(1);
        assertThat(omok(a).winCount()).isEqualTo(1);
        assertThat(omok(a).desertCount()).isZero();
    }

    @Test
    void a_bot_match_records_win_lose_but_skips_elo_and_bot_rows() {
        long a = human();
        long bot = bots.getBotIds().get(0);
        publisher.publishEvent(new OmokMatchCompleted(room(), List.of(a, bot), blackFiveSolo()));

        assertThat(omok(a).winCount()).isEqualTo(1);
        assertThat(omok(a).rating()).isEqualTo(UserGameStatsService.DEFAULT_RATING);
        assertThat(statsRows(bot)).isZero();
    }

    @Test
    void head_to_head_counts_only_the_other_team() {
        long a = human();
        long b = human();
        long c = human();
        long d = human();
        publisher.publishEvent(new OmokMatchCompleted(room(), List.of(a, b), blackFiveSolo()));     // a 가 b 를 이김
        OmokState teams = ENGINE.apply(ENGINE.startMatch(OmokMode.TEAMS_2V2, OmokRule.RENJU, 4, 0, 0), 1,
                new OmokAction.Resign()).newState();                                               // 0·2 팀 승
        publisher.publishEvent(new OmokMatchCompleted(room(), List.of(a, b, c, d), teams));

        HeadToHeadSource source = headToHead.stream().filter(h -> h.gameType().equals("OMOK")).findFirst().orElseThrow();
        var rows = source.against(a);
        assertThat(rows).extracting(HeadToHeadSource.Row::opponentId).containsExactlyInAnyOrder(b, d);
        HeadToHeadSource.Row vsB = rows.stream().filter(r -> r.opponentId() == b).findFirst().orElseThrow();
        assertThat(vsB.wins()).isEqualTo(2);
        assertThat(vsB.losses()).isZero();
    }
}
