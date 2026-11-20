package com.mirboard.domain.game.dambaduk.persistence;

import static com.mirboard.domain.game.dambaduk.DambadukBoards.playing;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.HeadToHeadSource;
import com.mirboard.domain.game.dambaduk.DambadukEngine;
import com.mirboard.domain.game.dambaduk.DambadukGameDefinition;
import com.mirboard.domain.game.dambaduk.DambadukStates;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.event.DambadukMatchCompleted;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
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

/** 담바둑 매치 종료가 매치 기록 + 게임별 전적(승패·개인전 ELO·탈주·시간패·무승부)으로 남는지 (룰 §7·§11, D-278). */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=dambaduk-recorder-test-secret-must-be-32-bytes-or-more"
})
class DambadukMatchRecorderIT {

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

    private static final DambadukEngine ENGINE = new DambadukEngine();

    private long human() {
        String name = "db" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats dambaduk(long userId) {
        return stats.get(userId, DambadukGameDefinition.ID);
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    private int statsRows(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_game_stats WHERE user_id = ? AND game_type = 'DAMBADUK'", Integer.class,
                userId);
    }

    /** 룰 §12.3 — 흑(좌석 0)이 C5 → C4 로 막아 21:16 으로 이긴 판. */
    private static DambadukState blackWins21to16() {
        DambadukState s = playing(2, 0,
                "+---+---+---+---+---+---+---+",
                "| .   a   . | .   .   b   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | B   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| A   .   . | .   .   .   B |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   b   .   . |",
                "+   +   +   +---+---+---+---+",
                "| .   .   A | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   a   . | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | .   .   .   . |",
                "+---+---+---+---+---+---+---+");
        return ENGINE.apply(s, 0, new DambadukAction.Move(6, List.of(Dir.UP), Dir.RIGHT)).newState();
    }

    /** 위 3줄 흑·가운데 3줄 백·맨 아래 빈 터, C3 → D3 로 막아 21:21·가장 큰 집 21:21 — 무승부. */
    private static DambadukState draw() {
        DambadukState s = playing(2, 0,
                "+---+---+---+---+---+---+---+",
                "| a   .   .   .   .   .   a |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   A   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   A   .   .   .   . |",
                "+---+---+---+   +---+---+---+",
                "| b   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   B   .   .   .   B   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   b |",
                "+---+---+---+---+---+---+---+",
                "| .   .   .   .   .   .   . |",
                "+---+---+---+---+---+---+---+");
        DambadukState n = ENGINE.apply(s, 0, new DambadukAction.Move(3, List.of(Dir.RIGHT), Dir.DOWN)).newState();
        assertThat(n.result().draw()).isTrue();
        return n;
    }

    @Test
    void a_two_player_human_match_records_rows_and_elo() {
        long a = human();
        long b = human();
        String room = room();
        publisher.publishEvent(new DambadukMatchCompleted(room, List.of(a, b), blackWins21to16()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dambaduk_match_results WHERE room_id = ?"
                + " AND seat_count = 2 AND end_reason = 'SEPARATED' AND NOT is_draw", Integer.class, room)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT p.color, p.final_rank, p.score, p.largest_home, p.exit_reason, p.is_win,"
                        + " p.deserted FROM dambaduk_match_participants p JOIN dambaduk_match_results r"
                        + " ON r.id = p.match_id WHERE r.room_id = ? ORDER BY p.seat", room))
                .extracting(r -> r.get("color") + "/" + r.get("final_rank") + "/" + r.get("score") + "/"
                        + r.get("largest_home") + "/" + r.get("exit_reason") + "/" + r.get("is_win") + "/"
                        + r.get("deserted"))
                .containsExactly("0/1/21/21/null/true/false", "1/2/16/16/null/false/false");
        assertThat(jdbc.queryForObject("SELECT payload_json FROM dambaduk_match_results WHERE room_id = ?",
                String.class, room)).contains("\"walls\"");
        // 신규(K=40), 같은 레이팅 2인 — 개인전 쌍대 ELO 도 일반 ELO 와 같은 ±20.
        assertThat(dambaduk(a).rating()).isEqualTo(1020);
        assertThat(dambaduk(b).rating()).isEqualTo(980);
        assertThat(dambaduk(a).winCount()).isEqualTo(1);
        assertThat(dambaduk(b).loseCount()).isEqualTo(1);
    }

    @Test
    void a_draw_changes_no_counts_or_rating_but_leaves_a_stats_row() {
        long a = human();
        long b = human();
        String room = room();
        publisher.publishEvent(new DambadukMatchCompleted(room, List.of(a, b), draw()));

        assertThat(statsRows(a)).isEqualTo(1);
        assertThat(statsRows(b)).isEqualTo(1);
        assertThat(dambaduk(a).winCount() + dambaduk(a).loseCount() + dambaduk(a).desertCount()).isZero();
        assertThat(dambaduk(a).rating()).isEqualTo(UserGameStatsService.DEFAULT_RATING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dambaduk_match_participants p"
                        + " JOIN dambaduk_match_results r ON r.id = p.match_id"
                        + " WHERE r.room_id = ? AND p.is_draw AND r.is_draw AND p.final_rank = 1", Integer.class, room))
                .isEqualTo(2);
    }

    @Test
    void three_players_the_timed_out_seat_loses_without_a_desertion_and_the_deserter_gets_one() {
        long a = human();
        long b = human();
        long c = human();
        DambadukState s = DambadukStates.threePlaying();                         // 좌석 0 차례
        s = ENGINE.apply(s, 0, new DambadukAction.TimeoutAct()).newState();       // 좌석 0 시간 초과 1번째
        s = ENGINE.apply(s, 1, ENGINE.timeoutMove(s, 1)).newState();
        s = ENGINE.apply(s, 2, ENGINE.timeoutMove(s, 2)).newState();
        s = ENGINE.apply(s, 0, new DambadukAction.TimeoutAct()).newState();       // 2번째 → 빠짐
        s = ENGINE.desert(s, 1).newState();                                       // 좌석 1 탈주 → 좌석 2 혼자
        assertThat(s.result().reason()).isEqualTo(EndReason.LAST_STANDING);
        publisher.publishEvent(new DambadukMatchCompleted(room(), List.of(a, b, c), s));

        assertThat(dambaduk(c).winCount()).isEqualTo(1);
        assertThat(dambaduk(a).loseCount()).isEqualTo(1);
        assertThat(dambaduk(a).desertCount()).as("시간패는 탈주가 아니다").isZero();
        assertThat(dambaduk(b).loseCount()).isEqualTo(1);
        assertThat(dambaduk(b).desertCount()).isEqualTo(1);
        assertThat(dambaduk(c).rating()).isGreaterThan(UserGameStatsService.DEFAULT_RATING);
        assertThat(dambaduk(a).rating()).isEqualTo(dambaduk(b).rating())           // 빠진 사람끼리 동순위
                .isLessThan(UserGameStatsService.DEFAULT_RATING);
    }

    @Test
    void a_bot_match_records_win_lose_but_skips_elo_and_bot_rows() {
        long a = human();
        long bot = bots.getBotIds().get(0);
        publisher.publishEvent(new DambadukMatchCompleted(room(), List.of(a, bot), blackWins21to16()));

        assertThat(dambaduk(a).winCount()).isEqualTo(1);
        assertThat(dambaduk(a).rating()).isEqualTo(UserGameStatsService.DEFAULT_RATING);
        assertThat(statsRows(bot)).isZero();
    }

    @Test
    void head_to_head_compares_final_ranks() {
        long a = human();
        long b = human();
        long c = human();
        publisher.publishEvent(new DambadukMatchCompleted(room(), List.of(a, b), blackWins21to16()));    // a > b
        DambadukState three = ENGINE.desert(ENGINE.desert(DambadukStates.threePlaying(), 1).newState(), 2).newState();
        publisher.publishEvent(new DambadukMatchCompleted(room(), List.of(a, b, c), three));            // a 1위, b=c

        HeadToHeadSource source = headToHead.stream().filter(h -> h.gameType().equals("DAMBADUK")).findFirst()
                .orElseThrow();
        Map<Long, HeadToHeadSource.Row> rows = new java.util.HashMap<>();
        source.against(b).forEach(r -> rows.put(r.opponentId(), r));
        assertThat(rows.get(a).losses()).isEqualTo(2);
        assertThat(rows.get(c).draws()).isEqualTo(1);
    }
}
