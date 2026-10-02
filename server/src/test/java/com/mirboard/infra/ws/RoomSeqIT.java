package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * `room:{id}:seq` 의 두 계약을 함께 지키는지 본다 — 둘은 서로 잡아당기는 관계다.
 *
 * <ul>
 *   <li><b>만료</b>: 방 하나당 고아 카운터 하나가 영구히 남으면 안 된다
 *       (`docs/redis-keys.md` — "모든 키는 EXPIRE 한다", seq TTL 6h).</li>
 *   <li><b>단조 증가</b>: 그렇다고 방이 살아있는 동안 사라지면 안 된다. 사라지면
 *       INCR 이 1부터 다시 시작해 클라의 seq gap 판정이 깨진다
 *       (CLAUDE.md Server-Authoritative). 그래서 TTL 은 발행 때마다 갱신된다.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-seq-test-secret-must-be-32-bytes-or-more"
})
class RoomSeqIT {

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

    @Autowired RoomSeq seqs;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void flush() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    private static String key(String roomId) {
        return "room:" + roomId + ":seq";
    }

    @Test
    void first_event_gives_the_counter_a_ttl() {
        String room = UUID.randomUUID().toString();

        seqs.next(room);

        // -1 = 무기한(고아 키), -2 = 키 없음.
        assertThat(redis.getExpire(key(room)))
                .as("첫 이벤트 발행 직후 seq 키 TTL")
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofHours(6).toSeconds());
    }

    @Test
    void every_event_refreshes_the_ttl_so_a_live_room_never_loses_its_counter() {
        String room = UUID.randomUUID().toString();
        seqs.next(room);
        // 긴 매치 도중 TTL 이 거의 다 닳은 상황을 흉내낸다.
        redis.expire(key(room), Duration.ofSeconds(5));

        seqs.next(room);

        assertThat(redis.getExpire(key(room)))
                .as("후속 이벤트가 TTL 을 6h 로 되돌린다")
                .isGreaterThan(Duration.ofHours(1).toSeconds());
    }

    @Test
    void counter_stays_monotonic_across_events() {
        String room = UUID.randomUUID().toString();

        assertThat(seqs.next(room)).isEqualTo(1L);
        assertThat(seqs.next(room)).isEqualTo(2L);
        assertThat(seqs.next(room)).isEqualTo(3L);
        assertThat(seqs.current(room)).isEqualTo(3L);
    }

    @Test
    void unused_room_has_no_counter_key_at_all() {
        String room = UUID.randomUUID().toString();

        assertThat(seqs.current(room)).isZero();
        assertThat(redis.hasKey(key(room))).isFalse();
    }
}
