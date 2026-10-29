package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-140 — 방 액션 락은 <b>내가 잡은 락만</b> 푼다. 락을 쥔 스레드가 TTL(2초)보다 오래 멈췄다 돌아오면(매치 기록 트랜잭션이 DB
 * 장애로 선 경우 — F-server-2) 그사이 다른 경로가 잡은 락을 예전엔 소유 확인 없는 {@code DEL} 로 지워, 세 번째 경로가 같은 방에
 * 동시에 들어올 수 있었다.
 */
@Testcontainers
class RoomActionLockIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private RoomActionLock lock;

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

    @BeforeEach
    void fresh() {
        redis.delete("room:r1:lock");
        lock = new RoomActionLock(redis);
    }

    @Test
    void the_holder_releases_its_own_lock() {
        assertThat(lock.tryAcquire("r1")).isTrue();
        assertThat(lock.tryAcquire("r1")).as("잡힌 동안은 못 잡는다").isFalse();

        lock.release("r1");

        assertThat(redis.hasKey("room:r1:lock")).isFalse();
        assertThat(lock.tryAcquire("r1")).isTrue();
    }

    @Test
    void a_late_release_after_expiry_does_not_delete_the_next_holders_lock() {
        assertThat(lock.tryAcquire("r1")).isTrue();
        // TTL 이 지나 다른 경로(다른 인스턴스·스레드)가 새로 잡았다.
        redis.delete("room:r1:lock");
        redis.opsForValue().set("room:r1:lock", "someone-else", 2, TimeUnit.SECONDS);

        lock.release("r1");

        assertThat(redis.opsForValue().get("room:r1:lock")).isEqualTo("someone-else");
    }

    @Test
    void a_thread_that_never_acquired_cannot_release() throws Exception {
        assertThat(lock.tryAcquire("r1")).isTrue();

        Thread other = Thread.ofVirtual().start(() -> lock.release("r1"));
        other.join();

        assertThat(redis.hasKey("room:r1:lock")).as("다른 스레드의 해제는 남의 락이다").isTrue();
        lock.release("r1");
        assertThat(redis.hasKey("room:r1:lock")).isFalse();
    }

    @Test
    void the_lock_still_expires_on_its_own() {
        assertThat(lock.tryAcquire("r1")).isTrue();

        Long ttlMs = redis.getExpire("room:r1:lock", TimeUnit.MILLISECONDS);

        assertThat(ttlMs).isBetween(1L, 2_000L);
    }
}
