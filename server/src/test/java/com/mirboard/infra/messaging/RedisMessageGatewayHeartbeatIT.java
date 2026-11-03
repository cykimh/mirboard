package com.mirboard.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-172 — Redis pub/sub 구독 연결 하트비트. 운영 Redis 는 D-171 부터 Fly 프록시(Flycast)를 거치므로 오래 조용한 구독 연결이
 * 끊길 수 있다. 하트비트가 주기적으로 그 연결에 메시지를 흘리고, 돌아오지 않으면 구독을 다시 붙인다.
 */
@Testcontainers
class RedisMessageGatewayHeartbeatIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private RedisMessageGateway gateway;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @AfterEach
    void stopGateway() throws Exception {
        if (gateway != null) gateway.destroy();
    }

    @Test
    void 하트비트가_구독_연결로_돌아온다() {
        gateway = new RedisMessageGateway(redis, factory, Duration.ofMillis(200));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(gateway.heartbeatsReceived()).isGreaterThanOrEqualTo(3));
    }

    @Test
    void 구독_연결이_끊겨도_다시_붙어_메시지가_온다() {
        gateway = new RedisMessageGateway(redis, factory, Duration.ofMillis(200));
        List<String> got = new CopyOnWriteArrayList<>();
        gateway.subscribe("it:hb:*", (channel, payload) -> got.add(payload));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(gateway.heartbeatsReceived()).isGreaterThanOrEqualTo(1));

        // 프록시가 조용한 연결을 끊은 상황 — 서버 쪽에서 구독 중인 연결만 끊는다
        try (var conn = factory.getConnection()) {
            var subscribers = conn.serverCommands().getClientList().stream()
                    .filter(c -> c.getLastCommand() != null && c.getLastCommand().contains("subscribe"))
                    .toList();
            assertThat(subscribers).isNotEmpty();
            for (var c : subscribers) {
                String[] addr = c.getAddressPort().split(":");
                conn.serverCommands().killClient(addr[0], Integer.parseInt(addr[1]));
            }
        }

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            redis.convertAndSend("it:hb:room", "after-kill");
            assertThat(got).contains("after-kill");
        });
    }

    @Test
    void 간격이_0이면_하트비트를_보내지_않는다() throws Exception {
        gateway = new RedisMessageGateway(redis, factory, Duration.ZERO);

        Thread.sleep(600);
        assertThat(gateway.heartbeatsReceived()).isZero();
    }
}
