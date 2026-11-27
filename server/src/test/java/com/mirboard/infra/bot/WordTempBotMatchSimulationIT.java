package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.wordtemp.FakeLiveHintScorer;
import com.mirboard.domain.game.wordtemp.WordTempConfig;
import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.WordTempGameDefinition;
import com.mirboard.domain.game.wordtemp.invariant.WordTempInvariantChecker;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintSource;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.words.WordTempWords;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameStompController;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * D-316·D-317 (S2) 완료 기준 — 정의 등록 → GameStartingEvent → 라운드 스타터(진짜 은행에서 후보 나누기) → 엔진 타이머(낱말 고르기 마감·
 * 봇 출제·봇 추리·단계 마감) → 매치 종료 → 방 상태 → 기록까지 전 배선을 <b>진짜 표</b>로 돈다. 그리고 설계서의 위험 R2("timer 가 채점기의
 * 결과함을 본다")를 {@code EngineTimerScheduler} 경로로 고정한다: 결과 도착 전 발화 → 재무장만(상태 그대로), 도착 뒤 → 반영, 8초 상한 뒤 →
 * 무효·개수 반환, 끄기 스위치 → 깨끗한 거절, 채점 중 탈주 → 차례 끝.
 *
 * <p>시간 배율 {@code mirboard.wordtemp.time-scale=0.01} 로 낱말 고르기·추리·결과·차례 끝·봇 간격만 줄인다(힌트 제한은 방 설정, 채점 기다림
 * 상한 8초는 그대로). 채점기는 가짜다 — 네트워크 호출 0.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=wordtemp-sim-test-secret-must-be-32-bytes-or-more-x",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.wordtemp.status=AVAILABLE",
        "mirboard.wordtemp.time-scale=0.01"
})
class WordTempBotMatchSimulationIT {

    private static final double SCALE = 0.01;

    @TestConfiguration
    static class FakeScorerConfig {
        @Bean
        @Primary
        FakeLiveHintScorer fakeLiveHintScorer() {
            return new FakeLiveHintScorer();
        }
    }

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

    @Autowired RoomService roomService;
    @Autowired BotUserRegistry bots;
    @Autowired WordTempStateStore stateStore;
    @Autowired WordTempGameDefinition definition;
    @Autowired UserRepository users;
    @Autowired UserGameStatsService stats;
    @Autowired DesertionService desertion;
    @Autowired GameStompController controller;
    @Autowired FakeLiveHintScorer scorer;
    @Autowired WordTempWords words;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetScorer() {
        scorer.available = true;
        scorer.requests.clear();
    }

    // ---- 봇 판 완주 (3~6인) ----

    @Test
    void three_bots_finish_a_match() {
        runAllBotMatch(3);
    }

    @Test
    void four_bots_finish_a_match() {
        runAllBotMatch(4);
    }

    @Test
    void five_bots_finish_a_match() {
        runAllBotMatch(5);
    }

    @Test
    void six_bots_finish_a_match() {
        runAllBotMatch(6);
    }

    /**
     * 사람은 손을 놓고 봇 둘이 논다 — 사람의 낱말은 무작위로 정해지고, 사람의 출제 차례는 힌트 0개로 5차까지 흘러(D-320 — 힌트가 없어도
     * 추리로 간다, 단서가 없는 봇은 패스) 출제 점수 없이 끝나며, 전적은 남고 ELO 는 1/4.
     */
    @Test
    void an_idle_human_with_bots_is_carried_to_the_end_by_the_engine_timer() {
        long human = newUser("wt_idle_");
        Room room = roomService.createRoom(human, "wt-idle", WordTempGameDefinition.ID, TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*힌트 제한*/ 1, RoomService.DEFAULT_STAKE, 3);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);

        WordTempState ended = awaitEnded(room, 90);
        assertThat(ended.result().endReason()).isEqualTo(EndReason.FINISHED);
        assertThat(ended.history()).hasSize(6); // 3인 × 보통 2라운드
        assertThat(ended.history()).filteredOn(t -> t.setterSeat() == 0).hasSize(2)
                .allMatch(t -> t.reason() == TurnEndReason.ALL_ATTEMPTS && t.setterGain() == 0
                        && t.revealed().size() == 5
                        && t.revealed().stream().allMatch(r -> r.text() == null && !r.floorMet()));
        assertThat(ended.history()).noneMatch(t -> t.reason() == TurnEndReason.NO_HINTS
                || t.reason() == TurnEndReason.THREE_ATTEMPTS);
        assertThat(scorer.requests).isEmpty();
        assertRecorded(room.roomId(), 3);
        GameStats humanStats = stats.get(human, WordTempGameDefinition.ID);
        assertThat(humanStats.winCount() + humanStats.loseCount()).as("사람 전적 1판").isEqualTo(1);
        // 봇이 낀 판도 끝나면 대기실로 돌아가지 않고(봇과 연습 방) FINISHED
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .until(() -> roomService.getRoom(room.roomId()).status() == RoomStatus.FINISHED);
    }

    // ---- 실시간 채점 경로 (가짜 채점기, 진짜 서버 경로) ----

    /**
     * 사람 셋. 출제자가 표 밖 힌트를 낸다 → 상태에 채점 중 → (결과 전) 엔진 타이머는 재무장만 → 결과 도착 → 반영. 이어서 답이 안 오는 힌트 →
     * 8초 뒤 무효·개수 반환. 끄기 스위치 → 거절(상태 그대로). 채점 중 출제자 탈주 → 차례 끝. 마지막 한 명 → 판 끝·기록.
     */
    @Test
    void live_scoring_goes_pending_then_applied_then_void_on_timeout_and_survives_kill_switch_and_desertion() {
        long a = newUser("wt_a_");
        long b = newUser("wt_b_");
        long c = newUser("wt_c_");
        Room created = roomService.createRoom(a, "wt-live", WordTempGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, /*힌트 제한*/ 120, RoomService.DEFAULT_STAKE, null);
        roomService.joinRoom(created.roomId(), b);
        roomService.joinRoom(created.roomId(), c);
        Room room = roomService.startNow(created.roomId(), a);
        String roomId = room.roomId();
        List<Long> players = room.playerIds();
        assertThat(players).hasSize(3);

        // 낱말 고르기 — 셋 다 고르면 바로 첫 출제 차례
        for (long user : players) {
            act(roomId, user, Map.of("@action", "SELECT_WORD", "round", 1, "index", 0));
        }
        WordTempState hinting = awaitState(roomId, s -> s.phase() == Phase.HINT);
        int setter = hinting.setterSeat();
        long setterUser = players.get(setter);
        String answer = hinting.answer().word();
        assertThat(hinting.answer().gloss()).as("진짜 은행의 뜻풀이가 상태에 있다").isNotBlank();

        // ① 표 밖 힌트 — 채점 중으로 저장되고 채점기에 (정답, 힌트)만 간다
        act(roomId, setterUser, hint(hinting, "가나다라마"));
        WordTempState pending = stateStore.load(roomId).orElseThrow();
        assertThat(pending.pendingHint()).isNotNull();
        assertThat(pending.pendingHint().text()).isEqualTo("가나다라마");
        assertThat(pending.hintCount()).isEqualTo(1);
        assertThat(scorer.requests).containsExactly(answer + "/가나다라마");
        assertThat(scorer.glosses).contains(hinting.answer().gloss());

        // ② 결과가 오기 전 — 엔진 타이머가 여러 번 발화해도(250ms 폴링) 상태는 그대로다
        sleep(900);
        assertThat(stateStore.load(roomId).orElseThrow()).isEqualTo(pending);
        // 채점 중에는 새 힌트를 받지 않는다(상태 그대로)
        act(roomId, setterUser, hint(hinting, "바사아자차"));
        assertThat(stateStore.load(roomId).orElseThrow()).isEqualTo(pending);

        // ③ 결과 도착 → 다음 폴링이 반영한다
        scorer.deliver(answer, "가나다라마", new LiveScore.Scored(64));
        WordTempState scored = awaitState(roomId, s -> s.pendingHint() == null);
        Hint first = scored.hints().get(0);
        assertThat(first.status()).isEqualTo(HintStatus.SCORED);
        assertThat(first.temp()).isEqualTo(64);
        assertThat(first.source()).isEqualTo(HintSource.LIVE);
        assertThat(scored.phase()).isEqualTo(Phase.HINT);
        assertThat(scored.liveRequests()).isEqualTo(1);

        // ④ 답이 오지 않는 힌트 — 8초 상한 뒤 무효, 개수를 돌려준다
        // 엔진 타이머(250ms 폴링)와 방 락이 겹치면 액션이 BUSY 로 조용히 거절된다 — 받아들여질 때까지 다시 보낸다
        actUntil(roomId, setterUser, hint(hinting, "바사아자차"), s -> s.hintCount() == 2);
        long asked = System.currentTimeMillis();
        WordTempState voided = Awaitility.await("기다림 상한 뒤 무효").atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).orElseThrow(), s -> s.pendingHint() == null);
        assertThat(System.currentTimeMillis() - asked).as("8초 상한").isGreaterThanOrEqualTo(7_500);
        assertThat(voided.hints().get(1).status()).isEqualTo(HintStatus.VOID);
        assertThat(voided.hintCount()).as("개수 반환").isEqualTo(1);
        assertThat(voided.phase()).isEqualTo(Phase.HINT);

        // ⑤ 끄기 스위치(또는 상한·키 없음) — 표 밖 힌트는 깨끗하게 거절되고 상태는 그대로, 표 안 힌트는 계속 된다
        scorer.available = false;
        int requestsBefore = scorer.requests.size();
        act(roomId, setterUser, hint(hinting, "타파하가나"));
        assertThat(stateStore.load(roomId).orElseThrow()).isEqualTo(voided);
        assertThat(scorer.requests).hasSize(requestsBefore);
        String tableHint = tableHintFor(answer, voided);
        // 엔진 타이머(250ms 폴링)가 방 락을 쥔 순간과 겹치면 액션이 BUSY 로 조용히 거절된다 — 받아들여질 때까지 다시 보낸다
        // (클라도 BUSY 를 한 번 다시 보낸다). 그대로 두면 이 걸음이 가끔 실패했다.
        for (int tries = 0; tries < 10 && stateStore.load(roomId).orElseThrow().equals(voided); tries++) {
            act(roomId, setterUser, hint(hinting, tableHint));
            sleep(60);
        }
        WordTempState withTable = stateStore.load(roomId).orElseThrow();
        assertThat(withTable.hints()).last().satisfies(h -> {
            assertThat(h.source()).isEqualTo(HintSource.TABLE);
            assertThat(h.status()).isEqualTo(HintStatus.SCORED);
        });
        assertThat(withTable.phase()).as("기준(70°) 아래 힌트라 단계는 그대로다").isEqualTo(Phase.HINT);

        // ⑥ 채점 중 출제자 탈주 — 차례가 그 자리에서 끝나고 채점 중이던 힌트는 버린다. 판은 둘이서 계속.
        scorer.available = true;
        act(roomId, setterUser, hint(hinting, "카타파하가"));
        assertThat(stateStore.load(roomId).orElseThrow().pendingHint()).isNotNull();
        assertThat(desertion.processDesertion(roomId, setterUser)).isTrue();
        WordTempState afterLeave = stateStore.load(roomId).orElseThrow();
        assertThat(afterLeave.pendingHint()).isNull();
        assertThat(afterLeave.alive(setter)).isFalse();
        assertThat(afterLeave.history()).last().satisfies(t -> assertThat(t.reason()).isEqualTo(TurnEndReason.SETTER_OUT));
        assertThat(afterLeave.ended()).isFalse();
        assertThat(roomService.getRoom(roomId).status()).isEqualTo(RoomStatus.IN_GAME);
        // 뒤늦은 결과는 아무것도 바꾸지 않는다 — 판은 다음 차례로 흘러간다
        scorer.deliver(answer, "카타파하가", new LiveScore.Scored(99));
        awaitState(roomId, s -> s.turnNo() >= 2);

        // ⑦ 한 명 더 빠지면 좌석 하나 — 판 끝, 기록
        long other = players.get((setter + 1) % 3);
        assertThat(desertion.processDesertion(roomId, other)).isTrue();
        WordTempState ended = stateStore.load(roomId).orElseThrow();
        assertThat(ended.result().endReason()).isEqualTo(EndReason.LAST_SEAT);
        assertRecorded(roomId, 3);
        long last = players.get((setter + 2) % 3);
        assertThat(stats.get(last, WordTempGameDefinition.ID).winCount()).isEqualTo(1);
        assertThat(stats.get(setterUser, WordTempGameDefinition.ID).desertCount()).isEqualTo(1);
        assertThat(roomService.getRoom(roomId).status()).isEqualTo(RoomStatus.FINISHED);
    }

    // ---- 도우미 ----

    /** 이 정답의 표 이웃 가운데 아직 안 낸, 1차 기준(70°) 아래인 낱말 하나 — 넘기면 힌트 단계가 그 자리에서 끝난다(D-320). */
    private String tableHintFor(String answer, WordTempState state) {
        return words.table().neighbors(answer).stream()
                .filter(n -> n.temp() < 70 && n.temp() >= 40)
                .filter(n -> state.hints().stream().noneMatch(h -> h.text().equals(n.hint())))
                .findFirst().orElseThrow().hint();
    }

    private static Map<String, Object> hint(WordTempState s, String text) {
        return Map.of("@action", "SUBMIT_HINT", "turnNo", s.turnNo(), "attempt", s.attempt(), "text", text);
    }

    private void act(String roomId, long userId, Map<String, Object> body) {
        controller.onAction(roomId, body, new AuthPrincipal(userId, "u" + userId));
    }

    /** 액션을 보내고, 조건이 맞을 때까지(BUSY 로 거절됐으면) 다시 보낸다 — 최대 20번. */
    private void actUntil(String roomId, long userId, Map<String, Object> body,
                          java.util.function.Predicate<WordTempState> accepted) {
        for (int i = 0; i < 20; i++) {
            act(roomId, userId, body);
            if (accepted.test(stateStore.load(roomId).orElseThrow())) {
                return;
            }
            sleep(100);
        }
        assertThat(accepted.test(stateStore.load(roomId).orElseThrow())).as("액션이 20번 안에 받아들여져야 한다").isTrue();
    }

    private WordTempState awaitState(String roomId, java.util.function.Predicate<WordTempState> condition) {
        return Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> stateStore.load(roomId).orElseThrow(), condition);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long newUser(String prefix) {
        return users.save(User.create(prefix + UUID.randomUUID().toString().substring(0, 6), "x",
                Clock.systemUTC())).getId();
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBotId, "wt-bot-sim-" + capacity, WordTempGameDefinition.ID,
                TeamPolicy.SEQUENTIAL, true, RoomService.DEFAULT_TARGET_SCORE, 90, RoomService.DEFAULT_STAKE, capacity);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(capacity);
        WordTempState ended = awaitEnded(room, 120);
        assertThat(ended.result().endReason()).isEqualTo(EndReason.FINISHED);
        assertThat(ended.history()).hasSize(capacity * 2); // 보통 = 2라운드, 모두 한 번씩 출제
        // 봇 출제자는 늘 표 안의 힌트를 낸다 — 힌트 없이 끝난 차수가 없고 실시간 채점 호출도 0
        assertThat(ended.history()).noneMatch(t -> t.reason() == TurnEndReason.NO_HINTS
                || t.reason() == TurnEndReason.THREE_ATTEMPTS);
        assertThat(ended.history()).allMatch(t -> !t.revealed().isEmpty()
                && t.revealed().stream().allMatch(r -> r.text() != null));
        // 1차 기준(70°)은 진짜 표의 모든 정답이 넘길 수 있다 — 봇은 1차를 늘 넘긴다
        assertThat(ended.history()).allMatch(t -> t.revealed().get(0).floorMet() && t.revealed().get(0).floor() == 70);
        assertThat(ended.history()).allMatch(t -> t.revealed().size() <= 5 && t.setterGain() <= 15 * t.round());
        assertThat(ended.liveRequests()).isZero();
        assertThat(scorer.requests).isEmpty();
        assertThat(ended.result().standings()).hasSize(capacity);
        assertRecorded(room.roomId(), capacity);
    }

    private GameContext context(Room room) {
        List<Integer> botSeats = new ArrayList<>();
        for (int seat = 0; seat < room.playerIds().size(); seat++) {
            if (bots.isBot(room.playerIds().get(seat))) {
                botSeats.add(seat);
            }
        }
        return new GameContext(room.roomId(), room.playerIds(), 0, 0, botSeats,
                Map.of(RoomOption.MATCH_LENGTH, "NORMAL", RoomOption.BOT_LEVEL, "NORMAL"), room.turnSeconds());
    }

    private WordTempState awaitEnded(Room room, int seconds) {
        String roomId = room.roomId();
        Awaitility.await("판 종료").atMost(seconds, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(WordTempState::ended).orElse(false));
        WordTempState state = stateStore.load(roomId).orElseThrow();
        WordTempConfig config = definition.config(context(room));
        assertThat(config.tuning().selectMs()).isEqualTo(Math.round(20_000 * SCALE));
        WordTempInvariantChecker.check(state, config);
        return state;
    }

    /** 매치 결과 1행 + 좌석 수만큼 참가자. */
    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("매치 기록").atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject("SELECT COUNT(*) FROM word_temp_match_results WHERE room_id = ?",
                        Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM word_temp_match_participants p"
                + " JOIN word_temp_match_results r ON r.id = p.match_id WHERE r.room_id = ?", Integer.class, roomId))
                .isEqualTo(seats);
    }
}
