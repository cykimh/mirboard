package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.physics.Walker;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.Unit;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * S6a 서버 경로 (D-228·D-229, `docs/plans/artillery-s6.md` §7) — 실제 STOMP 두 클라: 차례 주인이 {@code MOVE} 를 한 번에 하나씩 세 번 →
 * 상대가 {@code UNIT_MOVED} 셋을 연속 순번으로 받고, 중간 resync 가 확정 위치·남은 연료·엔진 남은 시간을 준다. {@code /preview} 프레임 →
 * 상대에게 순번 없는 {@code AIM_PREVIEW}(힘 없음), 이어서 {@code FIRE} 가 정상 해소되고 순번은 미리보기를 건너뛰지 않고 이어진다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=artillery-live-move-test-secret-must-be-32-bytes",
        "mirboard.artillery.status=AVAILABLE"
})
class ArtilleryLiveMoveIT {

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

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ArtilleryStateStore stateStore;

    @Autowired
    com.mirboard.domain.lobby.room.RoomService roomService;

    private final RestTemplate http = new RestTemplate();

    /** 두 사람의 본인 큐 — 정상 경로에서는 비어 있어야 한다(거절 ERROR 가 오면 실패 메시지에 싣는다). */
    private final BlockingQueue<JsonNode> errors = new LinkedBlockingQueue<>();

    @Test
    void moves_are_confirmed_in_sequence_and_previews_reach_the_other_player_unsequenced() throws Exception {
        String alice = registerAndLogin("s6alice");
        String bob = registerAndLogin("s6bob");
        long aliceId = userId(alice);
        String roomId = createRoom(alice);
        post(bob, "/api/rooms/" + roomId + "/join");
        post(alice, "/api/rooms/" + roomId + "/start");

        // 고르기 (D-224) — 둘 다 STOMP 로 고른다 → 공개·첫 차례
        BlockingQueue<JsonNode> aliceTopic = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> bobTopic = new LinkedBlockingQueue<>();
        StompSession aliceWs = connect(alice);
        StompSession bobWs = connect(bob);
        aliceWs.subscribe("/topic/room/" + roomId, collector(aliceTopic));
        bobWs.subscribe("/topic/room/" + roomId, collector(bobTopic));
        aliceWs.subscribe("/user/queue/room/" + roomId, collector(errors));
        bobWs.subscribe("/user/queue/room/" + roomId, collector(errors));
        Thread.sleep(200);
        Awaitility.await("고르기 단계").atMost(5, TimeUnit.SECONDS)
                .until(() -> stateStore.load(roomId).map(s -> s.phase() == Phase.PICKING).orElse(false));
        // 한 사람씩 — 동시에 보내면 방 액션 락에 하나가 BUSY 로 밀린다(앞 전이가 락을 쥔 채 방송하므로 차례로 보내도 BUSY 가 날 수 있다)
        sendRetryingBusy(aliceWs, "/app/room/" + roomId + "/action", Map.of("@action", "PICK", "unit", "SPROUT"),
                aliceTopic, "UNIT_PICKED");
        sendRetryingBusy(bobWs, "/app/room/" + roomId + "/action", Map.of("@action", "PICK", "unit", "SPROUT"),
                bobTopic, "UNITS_REVEALED");
        Awaitility.await("첫 차례").atMost(5, TimeUnit.SECONDS)
                .until(() -> stateStore.load(roomId).map(s -> s.phase() == Phase.AIMING).orElse(false));
        ArtilleryState start = stateStore.load(roomId).orElseThrow();
        assertThat(start.turnStartedAt()).as("차례 시계가 걸린다").isPositive();
        int seat = start.turnSeat();
        boolean aliceTurn = roomService.getRoom(roomId).playerIds().get(seat) == aliceId;
        StompSession mover = aliceTurn ? aliceWs : bobWs;
        BlockingQueue<JsonNode> moverTopic = aliceTurn ? aliceTopic : bobTopic;
        BlockingQueue<JsonNode> watcherTopic = aliceTurn ? bobTopic : aliceTopic;
        String watcherToken = aliceTurn ? bob : alice;
        int dir = safeDirection(start, seat);
        drainUntil(moverTopic, "TURN_CHANGED");
        long lastSeq = drainUntil(watcherTopic, "TURN_CHANGED").get("seq").asLong();

        // MOVE × 3 — 한 번에 하나(내 UNIT_MOVED 가 와야 다음)
        List<JsonNode> seen = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sendRetryingBusy(mover, "/app/room/" + roomId + "/action", Map.of("@action", "MOVE", "dx", dir * 5),
                    moverTopic, "UNIT_MOVED");
            JsonNode moved = drainUntil(watcherTopic, "UNIT_MOVED");
            seen.add(moved);
            assertThat(moved.get("seq").asLong()).as("연속 순번").isEqualTo(lastSeq + 1);
            lastSeq = moved.get("seq").asLong();
            assertThat(moved.get("payload").get("seat").asInt()).isEqualTo(seat);
            assertThat(moved.get("payload").get("path").size()).isEqualTo(2 * 5);
            if (i == 1) {
                JsonNode resync = resync(watcherToken, roomId);
                JsonNode view = resync.get("tableView");
                assertThat(view.get("units").get(seat).get("x").asInt()).isEqualTo(moved.get("payload").get("toX").asInt());
                assertThat(view.get("fuel").asInt()).isEqualTo(moved.get("payload").get("fuelLeft").asInt());
                assertThat(view.get("turnRemainingMs").asLong()).isPositive();
                assertThat(resync.get("eventSeq").asLong()).isEqualTo(lastSeq);
            }
        }
        assertThat(seen.get(2).get("payload").get("fuelLeft").asInt())
                .isLessThan(seen.get(0).get("payload").get("fuelLeft").asInt());

        // 미리보기 — 상대에게 순번 없이, 힘 없이
        Map<String, Object> preview = new HashMap<>();
        preview.put("@action", "PREVIEW");
        preview.put("angle", 70);
        preview.put("shell", "PEA");
        preview.put("n", 1);
        preview.put("power", 88);   // 숨김 — 서버가 버린다
        mover.send("/app/room/" + roomId + "/preview", preview);
        JsonNode aim = drainUntil(watcherTopic, "AIM_PREVIEW");
        assertThat(aim.has("seq")).as("미리보기는 순번을 쓰지 않는다").isFalse();
        assertThat(aim.get("payload").get("seat").asInt()).isEqualTo(seat);
        assertThat(aim.get("payload").get("angle").asInt()).isEqualTo(70);
        assertThat(aim.get("payload").get("shell").asText()).isEqualTo("PEA");
        assertThat(aim.get("payload").has("power")).isFalse();
        // 차례 아닌 사람의 미리보기는 버려진다
        (aliceTurn ? bobWs : aliceWs).send("/app/room/" + roomId + "/preview",
                Map.of("@action", "PREVIEW", "angle", 10, "n", 1));
        Thread.sleep(400);
        assertThat(watcherTopic.stream().map(e -> e.get("type").asText()))
                .as("차례 아닌 사람의 미리보기는 나가지 않는다").doesNotContain("AIM_PREVIEW");
        assertThat(errors).as("미리보기는 거절 ERROR 를 보내지 않는다").isEmpty();

        // FIRE — 남은 걸음을 싣고 정상 해소, 순번은 미리보기를 건너뛰지 않고 이어진다
        sendRetryingBusy(mover, "/app/room/" + roomId + "/action",
                Map.of("@action", "FIRE", "moveDx", dir * 2, "angle", 70, "power", 40), moverTopic, "SHOT_RESOLVED");
        JsonNode shot = drainUntil(watcherTopic, "SHOT_RESOLVED");
        assertThat(shot.get("seq").asLong()).isEqualTo(lastSeq + 1);
        assertThat(shot.get("payload").get("report").get("move").get("path").size()).isEqualTo(2 * 2);
        ArtilleryState after = stateStore.load(roomId).orElseThrow();
        assertThat(after.phase()).isIn(Phase.RESOLVING, Phase.ENDED);
        assertThat(after.unit(seat).x()).isEqualTo(start.unit(seat).x() + dir * 17);
    }

    /** 발밑 17칸을 떨어지지 않고 걸을 수 있는 쪽. */
    private static int safeDirection(ArtilleryState s, int seat) {
        Unit me = s.unit(seat);
        for (int dir : new int[] {1, -1}) {
            Walker.Walk w = Walker.walk(ArtilleryEngine.terrainOf(s), me.x(), me.footY(), dir * 17, 100, s.cloudLevel());
            if (w.fall() == null && w.steps() == 17) {
                return dir;
            }
        }
        throw new AssertionError("no safe direction to walk from " + me);
    }

    /**
     * 액션을 보내고 내 토픽에서 {@code type} 을 기다린다. 앞 전이가 아직 방 액션 락을 쥐고 있어 {@code BUSY} 가 오면 다시 보낸다(클라도
     * 한 번 다시 보낸다 — 설계 §6.2).
     */
    private void sendRetryingBusy(StompSession session, String destination, Map<String, Object> payload,
                                  BlockingQueue<JsonNode> topic, String type) throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            session.send(destination, payload);
            long until = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < until) {
                JsonNode error = errors.poll();
                if (error != null) {
                    assertThat(error.get("payload").get("code").asText()).as("only BUSY is retried: " + error)
                            .isEqualTo("BUSY");
                    Thread.sleep(50);
                    break;
                }
                JsonNode e = topic.poll(50, TimeUnit.MILLISECONDS);
                if (e != null && type.equals(e.get("type").asText())) {
                    return;
                }
            }
        }
        throw new AssertionError("no " + type + " after retries");
    }

    private JsonNode drainUntil(BlockingQueue<JsonNode> inbox, String type) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            JsonNode e = inbox.poll(5, TimeUnit.SECONDS);
            assertThat(e).as("waiting for " + type + " — own queues: " + errors).isNotNull();
            if (type.equals(e.get("type").asText())) {
                return e;
            }
        }
        throw new AssertionError("no " + type);
    }

    // ---------- helpers ----------

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        return client.connectAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
    }

    private StompFrameHandler collector(BlockingQueue<JsonNode> inbox) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.offer((JsonNode) payload);
            }
        };
    }

    private String registerAndLogin(String username) {
        var body = Map.of("username", username, "password", "validpass1");
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        http.postForObject("http://localhost:" + port + "/api/auth/register", new HttpEntity<>(body, headers), String.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> login = http.postForObject("http://localhost:" + port + "/api/auth/login",
                new HttpEntity<>(body, headers), Map.class);
        return (String) login.get("accessToken");
    }

    private long userId(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        @SuppressWarnings("unchecked")
        Map<String, Object> me = http.exchange("http://localhost:" + port + "/api/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class).getBody();
        return ((Number) me.get("userId")).longValue();
    }

    private String createRoom(String token) throws Exception {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> created = http.postForObject("http://localhost:" + port + "/api/rooms",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("name", "s6", "gameType", "ARTILLERY")), headers),
                Map.class);
        return (String) created.get("roomId");
    }

    private JsonNode resync(String token, String roomId) throws Exception {
        return get(token, "/api/rooms/" + roomId + "/resync");
    }

    private JsonNode get(String token, String path) throws Exception {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = http.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers),
                String.class).getBody();
        return objectMapper.readTree(body);
    }

    private void post(String token, String path) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        http.postForObject("http://localhost:" + port + path, new HttpEntity<>(headers), String.class);
    }
}
