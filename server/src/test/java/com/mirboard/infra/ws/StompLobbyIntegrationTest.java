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

    @Test
    void chat_message_is_broadcast_to_subscribers() throws Exception {
        String aliceToken = registerAndLogin("stomp_alice", "validpass1");
        String bobToken = registerAndLogin("stomp_bob", "validpass1");

        BlockingQueue<JsonNode> bobInbox = new ArrayBlockingQueue<>(8);

        StompSession aliceSession = connect(aliceToken);
        StompSession bobSession = connect(bobToken);

        bobSession.subscribe("/topic/lobby/chat", collector(bobInbox));
        // Give the subscription a moment to propagate before sending.
        Thread.sleep(150);

        aliceSession.send("/app/lobby/chat", Map.of("message", "안녕"));

        JsonNode env = bobInbox.poll(5, TimeUnit.SECONDS);
        assertThat(env).as("Bob must receive Alice's chat").isNotNull();
        assertThat(env.get("type").asText()).isEqualTo("CHAT");
        assertThat(env.get("payload").get("username").asText()).isEqualTo("stomp_alice");
        assertThat(env.get("payload").get("message").asText()).isEqualTo("안녕");
    }

    @Test
    void room_change_pushes_to_lobby_rooms_topic() throws Exception {
        String token = registerAndLogin("stomp_room", "validpass1");

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
