package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=game-stomp-test-secret-must-be-32-bytes-or-more"
})
class GameStompControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> MYSQL = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

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
    TichuGameStateStore stateStore;

    private final RestTemplate http = new RestTemplate();

    @Test
    void mahjong_leader_plays_and_event_broadcasts_to_subscribers() throws Exception {
        Map<String, String> tokens = registerAndLoginAll(
                List.of("ws_alice", "ws_bob", "ws_charlie", "ws_dave"));
        Map<String, Long> userIds = userIdsFromMe(tokens);

        // Alice creates and others join → game starts.
        String roomId = createRoom(tokens.get("ws_alice"), "ws-room");
        joinRoom(tokens.get("ws_bob"), roomId);
        joinRoom(tokens.get("ws_charlie"), roomId);
        joinRoom(tokens.get("ws_dave"), roomId);

        // Phase 5b: 라운드 시작 시 Dealing(8) 로 진입 — STOMP 라우팅 테스트만 검증하므로
        // 곧바로 Playing 상태로 덮어쓴다 (덱은 Dealing 의 hand+reserved 를 합쳐 Mahjong
        // 보유자가 리드하도록 구성).
        TichuState dealing = stateStore.load(roomId).orElseThrow();
        TichuState.Playing playing = forcePlayingFromDealing((TichuState.Dealing) dealing);
        stateStore.save(roomId, playing);
        int leadSeat = playing.trick().leadSeat();

        // Map seat → userId via Room.playerIds (alice/bob/charlie/dave joined in order).
        List<Long> playerIds = List.of(
                userIds.get("ws_alice"), userIds.get("ws_bob"),
                userIds.get("ws_charlie"), userIds.get("ws_dave"));
        long leaderUserId = playerIds.get(leadSeat);
        String leaderToken = tokenForUserId(tokens, userIds, leaderUserId);

        // Pick a subscriber that is NOT the leader.
        String subscriberToken = tokens.values().stream()
                .filter(t -> !t.equals(leaderToken))
                .findFirst().orElseThrow();

        BlockingQueue<JsonNode> inbox = new ArrayBlockingQueue<>(16);
        StompSession subscriberSession = connect(subscriberToken);
        subscriberSession.subscribe("/topic/room/" + roomId, collector(inbox));
        Thread.sleep(150);

        // Leader sends PlayCard with Mahjong (always legal for the holder on lead).
        StompSession leaderSession = connect(leaderToken);
        Card mahjong = Card.mahjong();
        Map<String, Object> action = new HashMap<>();
        action.put("@action", "PLAY_CARD");
        Map<String, Object> cardJson = new HashMap<>();
        cardJson.put("suit", null);
        cardJson.put("rank", mahjong.rank());
        cardJson.put("special", Special.MAHJONG.name());
        action.put("cards", List.of(cardJson));
        leaderSession.send("/app/room/" + roomId + "/action", action);

        // Expect a Played event broadcast.
        JsonNode env = null;
        for (int i = 0; i < 5; i++) {
            JsonNode candidate = inbox.poll(2, TimeUnit.SECONDS);
            if (candidate != null && "PLAYED".equals(candidate.get("type").asText())) {
                env = candidate;
                break;
            }
        }
        assertThat(env).as("Subscriber must receive a PLAYED event").isNotNull();
        assertThat(env.get("payload").get("seat").asInt()).isEqualTo(leadSeat);
        assertThat(env.get("seq").asLong()).isPositive();
    }

    // ---------- helpers ----------

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        return client.connectAsync(
                URI.create("ws://localhost:" + port + "/ws"),
                new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
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

    private Map<String, String> registerAndLoginAll(List<String> usernames) {
        Map<String, String> tokens = new HashMap<>();
        for (String u : usernames) {
            tokens.put(u, registerAndLogin(u, "validpass1"));
        }
        return tokens;
    }

    private String registerAndLogin(String username, String password) {
        var body = Map.of("username", username, "password", password);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        http.postForObject(
                "http://localhost:" + port + "/api/auth/register",
                new HttpEntity<>(body, headers), String.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> login = http.postForObject(
                "http://localhost:" + port + "/api/auth/login",
                new HttpEntity<>(body, headers), Map.class);
        return (String) login.get("accessToken");
    }

    private Map<String, Long> userIdsFromMe(Map<String, String> tokens) {
        Map<String, Long> ids = new HashMap<>();
        for (var e : tokens.entrySet()) {
            var headers = new HttpHeaders();
            headers.setBearerAuth(e.getValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> me = http.exchange(
                    "http://localhost:" + port + "/api/me",
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class).getBody();
            ids.put(e.getKey(), ((Number) me.get("userId")).longValue());
        }
        return ids;
    }

    private String createRoom(String token, String name) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of("name", name, "gameType", "TICHU"));
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> created = http.postForObject(
                "http://localhost:" + port + "/api/rooms",
                new HttpEntity<>(body, headers), Map.class);
        return (String) created.get("roomId");
    }

    private void joinRoom(String token, String roomId) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        http.postForObject(
                "http://localhost:" + port + "/api/rooms/" + roomId + "/join",
                new HttpEntity<>(headers), String.class);
    }

    private static String tokenForUserId(Map<String, String> tokens, Map<String, Long> userIds, long userId) {
        return userIds.entrySet().stream()
                .filter(e -> e.getValue() == userId)
                .map(e -> tokens.get(e.getKey()))
                .findFirst()
                .orElseThrow();
    }

    /** Dealing(8) 상태에서 reserved 까지 hand 로 합친 Playing 상태를 합성한다 (테스트 셋업용). */
    private static TichuState.Playing forcePlayingFromDealing(TichuState.Dealing dealing) {
        List<PlayerState> players = new ArrayList<>();
        int leadSeat = -1;
        for (PlayerState p : dealing.players()) {
            List<Card> fullHand = new ArrayList<>(p.hand());
            fullHand.addAll(dealing.reservedSecondHalf()
                    .getOrDefault(p.seat(), List.of()));
            players.add(p.withHand(fullHand));
            if (fullHand.stream().anyMatch(c -> c.is(Special.MAHJONG))) {
                leadSeat = p.seat();
            }
        }
        if (leadSeat < 0) {
            throw new IllegalStateException("Mahjong not found in dealt hands");
        }
        return new TichuState.Playing(players, TrickState.lead(leadSeat, null), -1);
    }
}
