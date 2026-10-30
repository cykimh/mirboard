package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.infra.ws.lobby.RoomLobbyEventPublisher;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
        "mirboard.jwt.secret=stomp-test-secret-must-be-32-bytes-or-more-pad"
})
class StompLobbyIntegrationTest {

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

    private final RestTemplate http = new RestTemplate();

    @Test
    void connect_without_jwt_fails() {
        var client = newStompClient();

        assertThatThrownBy(() ->
                client.connectAsync(wsUrl(), new WebSocketHttpHeaders(), new StompHeaders(),
                        new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS))
                .satisfies(t -> assertThat(t.getCause()).isNotNull());
    }

    // D-146 — 로비 채팅을 없앴다. 예전 주소로 보내도 아무도 받지 않는다(받는 컨트롤러가 없다).
    @Test
    void lobby_chat_is_gone() throws Exception {
        String aliceToken = registerAndLogin("stompalice", "validpass1");
        String bobToken = registerAndLogin("stompbob", "validpass1");

        BlockingQueue<JsonNode> bobInbox = new ArrayBlockingQueue<>(8);

        StompSession aliceSession = connect(aliceToken);
        StompSession bobSession = connect(bobToken);

        bobSession.subscribe("/topic/lobby/chat", collector(bobInbox));
        Thread.sleep(150);

        aliceSession.send("/app/lobby/chat", Map.of("message", "안녕"));

        assertThat(bobInbox.poll(2, TimeUnit.SECONDS)).as("로비 채팅은 배달되지 않는다").isNull();
    }

    @Test
    void room_change_pushes_to_lobby_rooms_topic() throws Exception {
        String token = registerAndLogin("stomproom", "validpass1");

        BlockingQueue<JsonNode> inbox = new ArrayBlockingQueue<>(8);
        StompSession session = connect(token);
        session.subscribe(RoomLobbyEventPublisher.LOBBY_ROOMS_TOPIC, collector(inbox));
        Thread.sleep(150);

        // Trigger a domain change via REST.
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        http.postForObject(
                "http://localhost:" + port + "/api/rooms",
                new HttpEntity<>(Map.of("name", "stomp-room", "gameType", "TICHU"), headers),
                String.class);

        JsonNode env = inbox.poll(5, TimeUnit.SECONDS);
        assertThat(env).as("Lobby subscriber must see the new room").isNotNull();
        assertThat(env.get("type").asText()).isEqualTo("ROOM_UPDATED");
        assertThat(env.get("payload").get("gameType").asText()).isEqualTo("TICHU");
        assertThat(env.get("payload").get("name").asText()).isEqualTo("stomp-room");
    }

    /**
     * D-158 — 서버가 STOMP 하트비트를 실제로 켠다. 예전엔 단순 브로커에 스케줄러가 없어 클라가 10초를 청해도 CONNECTED 가
     * {@code 0,0} 이었다 — 망이 바뀌어 죽은 소켓을 클라가 몰라 "연결됨"인 채로 멈췄다.
     */
    @Test
    void connected_frame_turns_on_heartbeats() throws Exception {
        String token = registerAndLogin("stomphb", "validpass1");
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        try {
            WebSocketStompClient client = newStompClient();
            client.setTaskScheduler(scheduler);
            StompHeaders connect = new StompHeaders();
            connect.add("Authorization", "Bearer " + token);
            connect.setHeartbeat(new long[] {10_000, 10_000});
            AtomicReference<StompHeaders> connected = new AtomicReference<>();

            StompSession session = client.connectAsync(URI.create(wsUrl()), new WebSocketHttpHeaders(), connect,
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void afterConnected(StompSession s, StompHeaders headers) {
                            connected.set(headers);
                        }
                    }).get(5, TimeUnit.SECONDS);

            assertThat(connected.get().getHeartbeat()).containsExactly(10_000, 10_000);
            session.disconnect();
        } finally {
            scheduler.shutdown();
        }
    }

    // ---------- helpers ----------

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private WebSocketStompClient newStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = newStompClient();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        try {
            return client.connectAsync(URI.create(wsUrl()), new WebSocketHttpHeaders(), connect,
                    new StompSessionHandlerAdapter() {})
                    .get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("STOMP connect timed out", e);
        }
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

}
