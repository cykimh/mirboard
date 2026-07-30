package com.mirboard.infra.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.infra.ws.RoomPresence;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-96 — 수평 확장 인프라 2종(프레즌스 · 데드라인 큐) 검증.
 *
 * <p>여기서 지키려는 불변식은 단일 인스턴스 테스트로는 안 보이는 것들이다:
 * <ul>
 *   <li>프레즌스가 <b>세션 카운터</b>라 탭 하나를 닫아도 접속 유지로 보인다
 *       (boolean 이었다면 재접속을 탈주로 오판한다).</li>
 *   <li>여러 폴러가 동시에 pop 해도 <b>한 항목은 정확히 하나</b>에게만 간다
 *       (아니면 중복 자동행동이 난다).</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=m3-infra-test-secret-must-be-32-bytes-min"
})
class DistributedInfraIT {

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

    @Autowired RoomPresence presence;
    @Autowired DeadlineQueue deadlines;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void flush() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    // ---------- 프레즌스 ----------

    @Test
    void presence_tracks_sessions_across_what_would_be_separate_instances() {
        String room = UUID.randomUUID().toString();
        // 서로 다른 인스턴스가 등록했다고 가정한 두 세션 — 저장소가 공유라 둘 다 보인다.
        presence.join("sess-A", 11L, room);
        presence.join("sess-B", 22L, room);

        assertThat(presence.hasLiveSession(11L, room)).isTrue();
        assertThat(presence.hasLiveSession(22L, room)).isTrue();
        assertThat(presence.viewers(room)).containsExactlyInAnyOrder(11L, 22L);
    }

    @Test
    void closing_one_of_two_tabs_keeps_the_user_present() {
        String room = UUID.randomUUID().toString();
        presence.join("tab-1", 11L, room);
        presence.join("tab-2", 11L, room);

        presence.leave("tab-1");

        // 여기서 false 가 나오면 탭 하나 닫은 사용자를 탈주로 처리하게 된다.
        assertThat(presence.hasLiveSession(11L, room)).isTrue();

        presence.leave("tab-2");
        assertThat(presence.hasLiveSession(11L, room)).isFalse();
        assertThat(presence.viewers(room)).isEmpty();
    }

    @Test
    void leave_returns_the_session_owner_so_disconnect_can_act_without_local_state() {
        String room = UUID.randomUUID().toString();
        presence.join("sess-X", 42L, room);

        var info = presence.leave("sess-X");

        assertThat(info).isPresent();
        assertThat(info.get().userId()).isEqualTo(42L);
        assertThat(info.get().roomId()).isEqualTo(room);
        // 모르는 세션은 조용히 empty — DISCONNECT 가 중복으로 와도 안전해야 한다.
        assertThat(presence.leave("sess-X")).isEmpty();
        assertThat(presence.leave("never-registered")).isEmpty();
    }

    // ---------- 데드라인 큐 ----------

    @Test
    void only_expired_deadlines_are_returned() {
        deadlines.schedule("turn", "room-1", Duration.ZERO);
        deadlines.schedule("turn", "room-2", Duration.ofHours(1));

        List<String> due = deadlines.pollDue("turn");

        assertThat(due).containsExactly("room-1");
        // pop 이므로 두 번째 폴링에는 안 나온다 — 재실행 방지의 근거.
        assertThat(deadlines.pollDue("turn")).isEmpty();
    }

    @Test
    void rescheduling_the_same_member_replaces_the_previous_deadline() {
        deadlines.schedule("turn", "room-1", Duration.ZERO);
        // 턴이 진행돼 타이머를 미룸 — 기존 항목이 남아 있으면 즉시 오발화한다.
        deadlines.schedule("turn", "room-1", Duration.ofHours(1));

        assertThat(deadlines.pollDue("turn")).isEmpty();
    }

    @Test
    void cancel_removes_a_pending_deadline() {
        deadlines.schedule("desertion", "room-1:7", Duration.ZERO);
        deadlines.cancel("desertion", "room-1:7");

        assertThat(deadlines.pollDue("desertion")).isEmpty();
    }

    @Test
    void concurrent_pollers_never_receive_the_same_deadline_twice() throws Exception {
        // 8개 폴러가 동시에 덤벼도 200개 항목이 정확히 한 번씩만 배분돼야 한다.
        // 여기서 중복이 나면 2인스턴스에서 중복 자동행동(턴 두 번 넘김)이 난다.
        int items = 200;
        for (int i = 0; i < items; i++) {
            deadlines.schedule("turn", "room-" + i, Duration.ZERO);
        }

        int pollers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(pollers);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> collected = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < pollers; i++) {
            pool.submit(() -> {
                start.await();
                for (int r = 0; r < 20; r++) {
                    collected.addAll(deadlines.pollDue("turn"));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(collected).hasSize(items);
        assertThat(collected.stream().distinct().count()).isEqualTo(items);
    }
}
