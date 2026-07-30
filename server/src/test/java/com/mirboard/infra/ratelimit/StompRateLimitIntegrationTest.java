package com.mirboard.infra.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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
 * D-90 — STOMP 인바운드 레이트리밋 end-to-end.
 *
 * <p>브라우저 수동 프로브로는 확인이 어려운 경로라(카운터 TTL 10s, 로그 버퍼링)
 * 통합 테스트로 못 박는다. 검증 두 가지:
 * <ol>
 *   <li>한도 내 채팅은 전부 브로드캐스트된다 — 정상 사용자를 막지 않는다.</li>
 *   <li>한도 초과분은 드롭되어 브로드캐스트되지 않는다 — 방어가 실제로 작동한다.</li>
 * </ol>
 * "통과만 확인"하면 레이트리밋이 아예 안 걸린 것과 구분되지 않으므로 둘 다 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=stomp-ratelimit-secret-must-be-32-bytes-min",
        "mirboard.ratelimit.enabled=true",
        // 인증/방생성은 넉넉히 — 채팅 버킷만 시험한다.
        "mirboard.ratelimit.buckets.auth.limit=1000",
        "mirboard.ratelimit.buckets.api-default.limit=1000",
        "mirboard.ratelimit.buckets.chat.limit=3",
        "mirboard.ratelimit.buckets.chat.window=1m"
})
class StompRateLimitIntegrationTest {

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

    @LocalServerPort
    int port;

    private final RestTemplate http = new RestTemplate();

    @Test
    void lobby_chat_beyond_the_bucket_is_dropped_while_the_allowance_still_flows()
            throws Exception {
        String senderToken = registerAndLogin("rlchatsend", "validpass1");
        String watcherToken = registerAndLogin("rlchatwatch", "validpass1");

        BlockingQueue<JsonNode> inbox = new ArrayBlockingQueue<>(32);
        StompSession sender = connect(senderToken);
        StompSession watcher = connect(watcherToken);
        watcher.subscribe("/topic/lobby/chat", collector(inbox));
        Thread.sleep(200);

        // 한도(3) 를 넘겨 6회 발행.
        for (int i = 0; i < 6; i++) {
            sender.send("/app/lobby/chat", Map.of("message", "spam-" + i));
        }

        // 앞의 3개는 반드시 도착해야 한다 — 정상 사용자를 막지 않는다는 쪽 검증.
        for (int i = 0; i < 3; i++) {
            JsonNode env = inbox.poll(5, TimeUnit.SECONDS);
            assertThat(env).as("한도 내 %d번째 채팅은 도착해야 한다", i).isNotNull();
            assertThat(env.get("type").asText()).isEqualTo("CHAT");
        }

        // 초과분은 드롭 — 더 이상 오지 않아야 한다. 방어가 실제로 작동하는 쪽 검증.
        JsonNode extra = inbox.poll(2, TimeUnit.SECONDS);
        assertThat(extra)
                .as("한도 초과 채팅은 드롭되어야 한다 (도착하면 레이트리밋 미작동)")
                .isNull();
    }

    @Test
    void a_second_user_is_not_affected_by_the_first_users_spam() throws Exception {
        String spammer = registerAndLogin("rlspammer1", "validpass1");
        String innocent = registerAndLogin("rlinnocent", "validpass1");

        BlockingQueue<JsonNode> inbox = new ArrayBlockingQueue<>(32);
        StompSession watcher = connect(registerAndLogin("rlwatcher1", "validpass1"));
        watcher.subscribe("/topic/lobby/chat", collector(inbox));
        Thread.sleep(200);

        StompSession spamSession = connect(spammer);
        for (int i = 0; i < 6; i++) {
            spamSession.send("/app/lobby/chat", Map.of("message", "flood-" + i));
        }
        // 스패머의 허용분 3개를 비운다.
        for (int i = 0; i < 3; i++) {
            inbox.poll(5, TimeUnit.SECONDS);
        }
        assertThat(inbox.poll(1, TimeUnit.SECONDS)).as("스패머 초과분은 드롭").isNull();

        // 다른 사용자는 같은 IP·같은 서버지만 자기 버킷이라 그대로 통과해야 한다.
        connect(innocent).send("/app/lobby/chat", Map.of("message", "hello"));
        JsonNode env = inbox.poll(5, TimeUnit.SECONDS);
        assertThat(env).as("다른 사용자는 영향받지 않아야 한다").isNotNull();
        assertThat(env.get("payload").get("message").asText()).isEqualTo("hello");
    }

    // ---------- helpers (StompLobbyIntegrationTest 와 동일 패턴) ----------

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        return client.connectAsync(URI.create("ws://localhost:" + port + "/ws"),
                        new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() {})
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

    private String registerAndLogin(String username, String password) {
        var body = Map.of("username", username, "password", password);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        http.postForObject("http://localhost:" + port + "/api/auth/register",
                new HttpEntity<>(body, headers), String.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> login = http.postForObject(
                "http://localhost:" + port + "/api/auth/login",
                new HttpEntity<>(body, headers), Map.class);
        return (String) login.get("accessToken");
    }
}
