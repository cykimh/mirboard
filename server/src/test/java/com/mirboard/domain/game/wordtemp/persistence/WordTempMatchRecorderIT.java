package com.mirboard.domain.game.wordtemp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.HeadToHeadSource;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.wordtemp.WordTempGameDefinition;
import com.mirboard.domain.game.wordtemp.event.WordTempMatchCompleted;
import com.mirboard.domain.game.wordtemp.rules.MatchRanking;
import com.mirboard.domain.game.wordtemp.scoring.LiveScoreStore;
import com.mirboard.domain.game.wordtemp.scoring.LiveScoreStore.Key;
import com.mirboard.domain.game.wordtemp.scoring.LiveScoreStore.Stored;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.MatchResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

/**
 * 낱말 온도 V25(D-316·D-317) — 매치 종료가 매치 기록 + 게임별 전적(승패·개인전 ELO·탈주·무승부·일부 동점)으로 남는지(룰 §13), 그리고
 * 실시간 점수 캐시({@code word_temp_live_scores})가 "처음 쓴 값이 정본·횟수만 센다"를 지키는지. 풀 컨텍스트라 Flyway V25 적용 +
 * Hibernate validate 도 같이 본다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=wordtemp-recorder-test-secret-must-be-32-bytes-or-more"
})
class WordTempMatchRecorderIT {

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
    @Autowired LiveScoreStore liveScores;

    private long human() {
        String name = "wt" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats wordTemp(long userId) {
        return stats.get(userId, WordTempGameDefinition.ID);
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    /** 좌석별 (출제 점수, 추리 점수, 살아 있는가)로 결과를 만든다 — 순위는 룰의 함수가 매긴다. */
    private static MatchResult result(EndReason reason, int[]... seats) {
        List<Seat> list = new ArrayList<>();
        for (int[] s : seats) {
            list.add(new Seat(s[2] == 1, s[0], s[1], List.of(AnswerEntry.of("바다", "자연")), List.of(), -1));
        }
        return MatchRanking.result(list, reason);
    }

    private static List<TurnSummary> turns() {
        return List.of(new TurnSummary(1, 1, 0, "바다", List.of(new Revealed(1, "갈매기", 71, 50, true)), List.of(1),
                1, TurnEndReason.SOLVED));
    }

    private List<String> participants(String room) {
        return jdbc.queryForList("SELECT p.seat, p.final_rank, p.total_points, p.setter_points, p.guess_points,"
                        + " p.is_win, p.deserted FROM word_temp_match_participants p JOIN word_temp_match_results r"
                        + " ON r.id = p.match_id WHERE r.room_id = ? ORDER BY p.seat", room).stream()
                .map(r -> r.get("final_rank") + "/" + r.get("total_points") + "/" + r.get("setter_points") + "/"
                        + r.get("guess_points") + "/" + r.get("is_win") + "/" + r.get("deserted")).toList();
    }

    @Test
    void a_human_match_records_rows_public_payload_and_free_for_all_elo() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();
        publisher.publishEvent(new WordTempMatchCompleted(room, List.of(a, b, c),
                result(EndReason.FINISHED, new int[] {6, 2, 1}, new int[] {1, 3, 1}, new int[] {0, 1, 1}), turns(), "v1"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM word_temp_match_results WHERE room_id = ?"
                + " AND end_reason = 'FINISHED'", Integer.class, room)).isEqualTo(1);
        assertThat(participants(room)).containsExactly("1/8/6/2/true/false", "2/4/1/3/false/false",
                "3/1/0/1/false/false");
        String payload = jdbc.queryForObject("SELECT payload_json FROM word_temp_match_results WHERE room_id = ?",
                String.class, room);
        assertThat(payload).contains("\"tableVersion\":\"v1\"").contains("\"answer\":\"바다\"").contains("갈매기")
                .contains("\"solvers\":[1]");
        assertThat(wordTemp(a).winCount()).isEqualTo(1);
        assertThat(wordTemp(b).loseCount()).isEqualTo(1);
        assertThat(wordTemp(c).loseCount()).isEqualTo(1);
        // 1위는 오르고 3위는 내린다(깎일 때 절반 D-297, 참가 점수 +6 D-301). 2위는 한 번 이기고 한 번 져서 참가 점수 근처.
        assertThat(wordTemp(a).rating()).isGreaterThan(wordTemp(b).rating());
        assertThat(wordTemp(b).rating()).isGreaterThan(wordTemp(c).rating());
        assertThat(wordTemp(a).rating()).isGreaterThan(UserGameStatsService.DEFAULT_RATING + EloCalculator.PARTICIPATION_BONUS);
        // D-305 — 같은 증감이 통합 레이팅에도 더해진다
        assertThat(stats.unified(a).rating()).isEqualTo(wordTemp(a).rating());
    }

    /** 일부만 동점(공동 1위 둘 + 3위) — 공동 1위는 둘 다 승, 서로는 비긴 것으로 쳐서 같은 증감. 무승부는 아니다. */
    @Test
    void a_partial_tie_makes_both_leaders_winners_with_equal_rating_changes() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();
        MatchResult tie = result(EndReason.FINISHED, new int[] {3, 2, 1}, new int[] {1, 4, 1}, new int[] {0, 1, 1});
        assertThat(tie.draw()).isFalse();
        publisher.publishEvent(new WordTempMatchCompleted(room, List.of(a, b, c), tie, turns(), "v1"));

        assertThat(participants(room)).containsExactly("1/5/3/2/true/false", "1/5/1/4/true/false",
                "3/1/0/1/false/false");
        assertThat(wordTemp(a).winCount()).isEqualTo(1);
        assertThat(wordTemp(b).winCount()).isEqualTo(1);
        assertThat(wordTemp(c).loseCount()).isEqualTo(1);
        assertThat(wordTemp(a).rating()).isEqualTo(wordTemp(b).rating())
                .isGreaterThan(UserGameStatsService.DEFAULT_RATING + EloCalculator.PARTICIPATION_BONUS);
        assertThat(wordTemp(c).rating()).isLessThan(UserGameStatsService.DEFAULT_RATING + EloCalculator.PARTICIPATION_BONUS);
        // 상대 전적: 공동 1위끼리는 무
        HeadToHeadSource source = headToHead.stream()
                .filter(h -> h.gameType().equals(WordTempGameDefinition.ID)).findFirst().orElseThrow();
        assertThat(source.against(a)).extracting(r -> r.opponentId() + ":" + r.wins() + "/" + r.losses() + "/" + r.draws())
                .containsExactlyInAnyOrder(b + ":0/0/1", c + ":1/0/0");
    }

    /** 빠진 사람 없이 모두 같은 점수 — 무승부: 승패 0, ELO 변화 없이 참가 점수만. */
    @Test
    void everyone_on_the_same_score_is_a_draw() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();
        MatchResult draw = result(EndReason.FINISHED, new int[] {1, 1, 1}, new int[] {2, 0, 1}, new int[] {0, 2, 1});
        assertThat(draw.draw()).isTrue();
        publisher.publishEvent(new WordTempMatchCompleted(room, List.of(a, b, c), draw, turns(), "v1"));

        assertThat(participants(room)).containsExactly("1/2/1/1/false/false", "1/2/2/0/false/false",
                "1/2/0/2/false/false");
        for (long user : List.of(a, b, c)) {
            assertThat(wordTemp(user).winCount() + wordTemp(user).loseCount() + wordTemp(user).desertCount()).isZero();
            assertThat(wordTemp(user).rating())
                    .isEqualTo(UserGameStatsService.DEFAULT_RATING + EloCalculator.PARTICIPATION_BONUS);
        }
    }

    /** 탈주자는 점수와 무관하게 최하위 — 패 + 탈주 수, 참가 점수 없음. 남은 둘 중 점수 높은 쪽이 승. */
    @Test
    void a_deserter_ranks_last_and_gets_a_desertion() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();
        publisher.publishEvent(new WordTempMatchCompleted(room, List.of(a, b, c),
                result(EndReason.FINISHED, new int[] {9, 0, 0}, new int[] {1, 1, 1}, new int[] {0, 1, 1}), turns(), "v1"));

        assertThat(participants(room)).containsExactly("3/9/9/0/false/true", "1/2/1/1/true/false",
                "2/1/0/1/false/false");
        assertThat(wordTemp(a).desertCount()).isEqualTo(1);
        assertThat(wordTemp(a).loseCount()).isEqualTo(1);
        assertThat(wordTemp(a).rating()).isLessThan(UserGameStatsService.DEFAULT_RATING);
        assertThat(wordTemp(b).winCount()).isEqualTo(1);
    }

    /** 봇이 낀 판 — 사람 전적은 남고 ELO 는 1/4 만(D-299), 봇 계정은 전적을 쌓지 않는다. */
    @Test
    void a_match_with_bots_keeps_the_human_record_at_a_quarter_elo_and_no_bot_stats() {
        long a = human();
        long bot1 = bots.getBotIds().get(0);
        long bot2 = bots.getBotIds().get(1);
        String room = room();
        publisher.publishEvent(new WordTempMatchCompleted(room, List.of(a, bot1, bot2),
                result(EndReason.FINISHED, new int[] {6, 2, 1}, new int[] {1, 1, 1}, new int[] {0, 1, 1}), turns(), "v1"));

        assertThat(participants(room)).hasSize(3);
        assertThat(wordTemp(a).winCount()).isEqualTo(1);
        int gain = wordTemp(a).rating() - UserGameStatsService.DEFAULT_RATING;
        assertThat(gain).as("봇 판은 1/4 + 참가 점수 2").isBetween(3, 14);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_game_stats WHERE user_id IN (?, ?)"
                + " AND game_type = 'WORD_TEMP'", Integer.class, bot1, bot2)).isZero();
    }

    // ---- 실시간 점수 캐시 ----

    @Test
    void the_live_score_cache_keeps_the_first_value_counts_hits_and_knows_nothing_but_words() {
        String answer = "답" + UUID.randomUUID().toString().substring(0, 6);
        Key key = new Key(answer, "짠맛", "claude-sonnet-5-5", "score-v2");
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        int already = liveScores.createdSince(before);

        assertThat(liveScores.findAndTouch(key)).isEmpty();
        assertThat(liveScores.insertFirst(key, new Stored(true, 64))).isEqualTo(new Stored(true, 64));
        // 다른 인스턴스가 늦게 쓴 값은 버려진다 — 처음 쓴 값이 정본
        assertThat(liveScores.insertFirst(key, new Stored(true, 80))).isEqualTo(new Stored(true, 64));
        assertThat(liveScores.findAndTouch(key)).contains(new Stored(true, 64));
        assertThat(liveScores.findAndTouch(key)).contains(new Stored(true, 64));
        assertThat(jdbc.queryForObject("SELECT hits FROM word_temp_live_scores WHERE answer = ? AND hint = '짠맛'",
                Integer.class, answer)).isEqualTo(3);
        // 모델·기준문 버전이 다르면 다른 쌍이다
        assertThat(liveScores.findAndTouch(new Key(answer, "짠맛", "claude-sonnet-5-5", "score-v1"))).isEmpty();
        assertThat(liveScores.insertFirst(new Key(answer, "베토벤", "claude-sonnet-5-5", "score-v2"),
                new Stored(false, 0))).isEqualTo(new Stored(false, 0));
        assertThat(liveScores.createdSince(before)).isEqualTo(already + 2);
        assertThat(liveScores.createdSince(Instant.now().plus(1, ChronoUnit.HOURS))).isZero();

        // 낱말과 횟수뿐 — 사용자·방을 가리키는 열이 없다
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'word_temp_live_scores'", String.class))
                .containsExactlyInAnyOrder("answer", "hint", "model", "prompt_version", "valid", "score", "hits",
                        "created_at", "last_seen_at");
    }
}
