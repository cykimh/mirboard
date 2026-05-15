package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Phase 8B — 인-게임 채팅 STOMP 검증. 멤버 송신 → 토픽 fan-out, 비-멤버 송신 →
 * broadcast 안 됨 (멤버 검증으로 drop).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=roomchat-test-secret-must-be-32-bytes-or-more"
})
class RoomChatStompIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    void member_chat_is_broadcast_to_room_topic() throws Exception {
        String hostToken = registerAndLogin("rc_host1", "validpass1");
        String guestToken = registerAndLogin("rc_guest1", "validpass1");
        String roomId = createRoomAndJoin(hostToken, guestToken);

        BlockingQueue<JsonNode> hostInbox = new ArrayBlockingQueue<>(8);
        StompSession hostSession = connect(hostToken);
        StompSession guestSession = connect(guestToken);

        hostSession.subscribe("/topic/room/" + roomId + "/chat", collector(hostInbox));
        Thread.sleep(150);

        guestSession.send("/app/room/" + roomId + "/chat", Map.of("message", "굿럭"));

        JsonNode env = hostInbox.poll(5, TimeUnit.SECONDS);
        assertThat(env).as("Host must receive guest's room chat").isNotNull();
        assertThat(env.get("type").asText()).isEqualTo("CHAT");
        assertThat(env.get("payload").get("username").asText()).isEqualTo("rc_guest1");
        assertThat(env.get("payload").get("message").asText()).isEqualTo("굿럭");
    }

    @Test
    void non_member_chat_is_dropped() throws Exception {
        String hostToken = registerAndLogin("rc_host2", "validpass1");
        String intruderToken = registerAndLogin("rc_intruder", "validpass1");
        String roomId = createRoomOnly(hostToken);

        BlockingQueue<JsonNode> hostInbox = new ArrayBlockingQueue<>(8);
        StompSession hostSession = connect(hostToken);
        StompSession intruderSession = connect(intruderToken);

        hostSession.subscribe("/topic/room/" + roomId + "/chat", collector(hostInbox));
        Thread.sleep(150);

        // Intruder 는 방에 join 안 했고 spectator 도 아님 → drop.
        intruderSession.send("/app/room/" + roomId + "/chat",
                Map.of("message", "도배도배도배"));

        JsonNode env = hostInbox.poll(2, TimeUnit.SECONDS);
        assertThat(env)
                .as("Non-member chat must be silently dropped")
                .isNull();
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

    private String createRoomOnly(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        @SuppressWarnings("unchecked")
        Map<String, Object> room = http.postForObject(
                "http://localhost:" + port + "/api/rooms",
                new HttpEntity<>(Map.of("name", "chat-room", "gameType", "TICHU"), headers),
                Map.class);
        return (String) room.get("roomId");
    }

    private String createRoomAndJoin(String hostToken, String guestToken) {
        String roomId = createRoomOnly(hostToken);
        var headers = new HttpHeaders();
        headers.setBearerAuth(guestToken);
        http.postForObject(
                "http://localhost:" + port + "/api/rooms/" + roomId + "/join",
                new HttpEntity<>(headers), String.class);
        return roomId;
    }
}
