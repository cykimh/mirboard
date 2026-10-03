package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.admin.AdminRole;
import com.mirboard.domain.admin.AdminRoleRepository;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.infra.ws.RoomSeq;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-122 — 끝난 방(FINISHED)은 더 진행하지 않는다.
 *
 * <p>탈주 조기 종료·강제 종료는 방을 FINISHED 로 만들 뿐 직전 액션이 걸어 둔 턴 데드라인을
 * 지우지 않았고, 타임아웃 스케줄러는 방 상태를 보지 않았다. 그래서 턴 제한이 있는 방에서
 * 매치가 끝난 뒤에도 데드라인이 발화해 버려진 라운드를 자동 진행하고(유령 드레인 포함)
 * {@code CARD_PLAYED}·{@code TURN_CHANGED} 를 계속 내보냈다 — 종료 패널 위에서 트릭이 움직이고
 * '내 차례' 배지가 다시 떴다.
 *
 * <p>사람 2명·턴 제한 1초 스컬킹 방(1라운드 Bidding)에서 ① 한 명이 나가 탈주 조기 종료
 * ② 호스트 강제 종료, 두 경로 모두 (a) 그 방의 턴 데드라인이 취소되고 (b) 데드라인이 발화했을
 * 시각을 넉넉히 지나도 라운드·매치 상태와 이벤트 seq 가 그대로인지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=finished-stops-progress-secret-32-bytes-min",
        "mirboard.bot.delay-millis=0",
        // 턴 제한 1초를 폴링 지연 없이 발화시키려고 주기를 최소로 둔다(TurnTimeoutSchedulerIT 와 같음).
        "mirboard.scheduling.poll-interval-millis=50"
})
class FinishedRoomStopsProgressIT {

    private static final int TURN_SECONDS = 1;
    /** 데드라인(1s) + 폴링·락 재시도 여유. 이 동안 아무것도 바뀌지 않아야 한다. */
    private static final Duration QUIET = Duration.ofMillis(2_500);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redis;
    @Autowired SkullKingStateStore stateStore;
    @Autowired SkullKingMatchStateStore matchStateStore;
    @Autowired RoomSeq seqs;
    @Autowired AdminRoleRepository adminRoles;
    @Autowired Clock clock;
    private final Map<String, Long> userIds = new java.util.HashMap<>();

    @Test
    void a_desertion_that_ends_the_match_stops_the_turn_timer() throws Exception {
        Table t = startTwoPlayerSkullKing("fp1");

        leave(t, 1); // 2인 방에서 한 명 탈주 → 잔존 1 < 2 → MATCH_ENDED (§13-⑲).

        assertThat(roomStatus(t)).isEqualTo("FINISHED");
        SkullKingMatchState match = matchStateStore.load(t.roomId).orElseThrow();
        assertThat(match.isMatchOver()).isTrue();
        assertStopped(t);
    }

    @Test
    void an_abort_stops_the_turn_timer() throws Exception {
        Table t = startTwoPlayerSkullKing("fp2");

        mockMvc.perform(post("/api/rooms/" + t.roomId + "/abort")
                        .header("Authorization", bearer(t.tokens.get(0))))
                .andExpect(status().isNoContent());

        assertThat(roomStatus(t)).isEqualTo("FINISHED");
        assertStopped(t);
    }

    @Test
    void an_admin_abort_stops_the_turn_timer() throws Exception {
        Table t = startTwoPlayerSkullKing("fp3");
        String adminToken = registerAndLogin("fp3_admin");
        adminRoles.save(new AdminRole(userIdOf(adminToken), clock.instant()));

        mockMvc.perform(post("/api/admin/rooms/" + t.roomId + "/abort")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        assertThat(roomStatus(t)).isEqualTo("FINISHED");
        assertStopped(t);
    }

    /**
     * 강제 종료는 방 액션 락 안에서 한다. 락 밖에서 FINISHED 로 만들면, 락을 쥐고 IN_GAME 을
     * 확인한 직후의 액션(사람·봇·타임아웃)이 그대로 적용·브로드캐스트됐다 — 스컬킹이면 그 액션이
     * 끝낸 라운드의 정산과 다음 라운드 시작까지. 진행 중 액션을 락 보유로 흉내 내고, 그동안 abort 가
     * 기다리는지(방이 아직 IN_GAME) 본 뒤 락을 놓으면 끝나는지 본다.
     */
    @Test
    void an_abort_waits_for_the_in_flight_action_to_release_the_room_lock() throws Exception {
        Table t = startTwoPlayerSkullKing("fp4");
        String lockKey = "room:" + t.roomId + ":lock";
        redis.opsForValue().set(lockKey, "in-flight-action", Duration.ofSeconds(30));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> abort = pool.submit(() -> mockMvc.perform(
                            post("/api/rooms/" + t.roomId + "/abort")
                                    .header("Authorization", bearer(t.tokens.get(0))))
                    .andReturn().getResponse().getStatus());

            Awaitility.await()
                    .during(Duration.ofMillis(500))
                    .atMost(Duration.ofSeconds(2))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> "IN_GAME".equals(rawStatus(t.roomId)));
            assertThat(abort.isDone()).as("액션이 락을 쥔 동안 abort 는 기다린다").isFalse();

            redis.delete(lockKey); // 진행 중이던 액션이 끝났다.

            assertThat(abort.get(5, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(rawStatus(t.roomId)).isEqualTo("FINISHED");
            assertThat(pendingTurnDeadlines(t.roomId)).isEmpty();
        } finally {
            redis.delete(lockKey);
            pool.shutdownNow();
        }
    }

    /** 방 해시의 status 를 직접 읽는다(MockMvc 를 다른 스레드와 동시에 쓰지 않으려고). */
    private String rawStatus(String roomId) {
        Object status = redis.opsForHash().get("room:" + roomId, "status");
        return status == null ? null : status.toString();
    }

    /**
     * (a) 턴 데드라인이 취소됐고 (b) 그 데드라인이 발화했을 시각이 지나도 상태·seq 가
     * 그대로다. (b) 만으로는 "취소는 안 됐지만 발화 쪽 가드가 막았다"와 구분이 안 되므로 둘 다 본다.
     */
    private void assertStopped(Table t) {
        assertThat(pendingTurnDeadlines(t.roomId))
                .as("끝난 방의 턴 데드라인은 취소된다")
                .isEmpty();

        SkullKingState stateAtEnd = stateStore.load(t.roomId).orElseThrow();
        SkullKingMatchState matchAtEnd = matchStateStore.load(t.roomId).orElseThrow();
        long seqAtEnd = seqs.current(t.roomId);
        assertThat(stateAtEnd).isInstanceOf(SkullKingState.Bidding.class);

        Awaitility.await()
                .during(QUIET)
                .atMost(QUIET.plusSeconds(2))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(t.roomId).orElseThrow().equals(stateAtEnd)
                        && matchStateStore.load(t.roomId).orElseThrow().equals(matchAtEnd)
                        && seqs.current(t.roomId) == seqAtEnd);
    }

    private List<String> pendingTurnDeadlines(String roomId) {
        Set<String> members = redis.opsForZSet().range("deadlines:" + TurnTimeoutScheduler.KIND,
                0, -1);
        List<String> mine = new ArrayList<>();
        if (members != null) {
            members.stream().filter(m -> m.startsWith(roomId + "#")).forEach(mine::add);
        }
        return mine;
    }

    // ---------- helpers ----------

    /** 사람 2명, 턴 제한 1초 스컬킹 방 — 1라운드 Bidding 에서 첫 턴 데드라인이 걸린다. */
    private Table startTwoPlayerSkullKing(String prefix) throws Exception {
        List<String> tokens = List.of(registerAndLogin(prefix + "_a"),
                registerAndLogin(prefix + "_b"));
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", bearer(tokens.get(0)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "stop-room", "gameType", "SKULL_KING",
                                "capacity", 2, "turnSeconds", TURN_SECONDS))))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("roomId").asText();
        mockMvc.perform(post("/api/rooms/" + roomId + "/join")
                        .header("Authorization", bearer(tokens.get(1))))
                .andExpect(status().isOk());
        for (String token : tokens) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/ready")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ready\":true}"))
                    .andExpect(status().isOk());
        }
        Table t = new Table(roomId, tokens);
        assertThat(roomStatus(t)).isEqualTo("IN_GAME");
        assertThat(pendingTurnDeadlines(roomId))
                .as("전제: 1라운드 시작과 함께 턴 데드라인이 걸려 있다")
                .hasSize(1);
        return t;
    }

    private void leave(Table t, int seat) throws Exception {
        mockMvc.perform(post("/api/rooms/" + t.roomId + "/leave")
                        .header("Authorization", bearer(t.tokens.get(seat))))
                .andExpect(status().isNoContent());
    }

    private String roomStatus(Table t) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/rooms/" + t.roomId)
                        .header("Authorization", bearer(t.tokens.get(0))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode room = objectMapper.readTree(r.getResponse().getContentAsString());
        return room.get("status").asText();
    }

    private String registerAndLogin(String username) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        userIds.put(token, json.get("user").get("userId").asLong());
        return token;
    }

    private long userIdOf(String token) {
        return userIds.get(token);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Table(String roomId, List<String> tokens) {
    }
}
