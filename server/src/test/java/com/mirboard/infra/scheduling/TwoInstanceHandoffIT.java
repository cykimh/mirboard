package com.mirboard.infra.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mirboard.infra.ws.RoomPresence;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-96 5단계 — <b>2-인스턴스 인계 증명</b>.
 *
 * <p>"단일 인스턴스에서 안 깨졌다"는 수평 확장의 증명이 아니다. 여기서는 같은
 * Redis/Postgres 를 물린 <b>독립 Spring 컨텍스트 2개</b>를 띄우고, in-memory 시절이라면
 * 반드시 실패했을 세 가지를 확인한다.
 *
 * <ol>
 *   <li><b>교차 발화</b> — A 가 건 데드라인을 B 가 처리한다. 구 ScheduledFuture 는
 *       프로세스에 묶여 있어 불가능했다.</li>
 *   <li><b>인스턴스 사망 인계</b> — A 를 종료해도 A 가 건 데드라인을 B 가 처리한다.
 *       구조상 구 구현은 그 타이머를 영영 잃었다(탈주 미확정 버그).</li>
 *   <li><b>중복 실행 0</b> — 두 인스턴스가 동시에 폴링해도 한 항목은 한 번만.</li>
 * </ol>
 *
 * <p>웹 서버·STOMP 없이 컨텍스트만 띄운다(WebApplicationType.NONE) — 검증 대상은
 * 스케줄링 인계이지 HTTP 가 아니다.
 */
@Testcontainers
class TwoInstanceHandoffIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /** 각 인스턴스가 처리한 member 를 모으는 공유 수집기 — 테스트 JVM 안이라 공유 가능. */
    static final ConcurrentLinkedQueue<String> HANDLED = new ConcurrentLinkedQueue<>();
    static final AtomicInteger HANDLE_CALLS = new AtomicInteger();

    /** 실제 게임 핸들러 대신 계수용 핸들러를 꽂아, 인계 자체만 격리해 본다. */
    @Configuration
    static class ProbeConfig {
        @Bean
        DeadlineHandler probeHandler() {
            return new DeadlineHandler() {
                @Override
                public String kind() {
                    return "probe";
                }

                @Override
                public void handle(String member) {
                    HANDLE_CALLS.incrementAndGet();
                    HANDLED.add(member);
                }
            };
        }
    }

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;

    private static ConfigurableApplicationContext boot(String name) {
        return new SpringApplicationBuilder(com.mirboard.MirboardApplication.class, ProbeConfig.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.application.name=" + name,
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.data.redis.host=" + REDIS.getHost(),
                        "spring.data.redis.port=" + REDIS.getFirstMappedPort(),
                        "mirboard.jwt.secret=two-instance-test-secret-must-be-32-bytes",
                        // 폴링을 짧게 — 테스트 대기 시간을 줄인다.
                        "mirboard.scheduling.poll-interval-millis=200")
                .run();
    }

    private static ConfigurableApplicationContext instanceA() {
        if (instanceA == null || !instanceA.isActive()) {
            instanceA = boot("mirboard-A");
        }
        return instanceA;
    }

    private static ConfigurableApplicationContext instanceB() {
        if (instanceB == null || !instanceB.isActive()) {
            instanceB = boot("mirboard-B");
        }
        return instanceB;
    }

    @BeforeEach
    void reset() {
        // 컨텍스트를 띄워 두 인스턴스가 같은 Redis 를 보게 한다.
        instanceA();
        instanceB();
        instanceA.getBean(RedisConnectionFactory.class).getConnection()
                .serverCommands().flushDb();
        HANDLED.clear();
        HANDLE_CALLS.set(0);
    }

    @AfterAll
    static void tearDown() {
        if (instanceA != null && instanceA.isActive()) instanceA.close();
        if (instanceB != null && instanceB.isActive()) instanceB.close();
    }

    @Test
    void a_deadline_scheduled_on_one_instance_is_executed_somewhere() {
        DeadlineQueue queueA = instanceA.getBean(DeadlineQueue.class);
        String member = "cross-fire-" + UUID.randomUUID();

        queueA.schedule("probe", member, Duration.ZERO);

        // 어느 인스턴스가 잡든 상관없다 — 중요한 건 "잡힌다"는 것.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(HANDLED).contains(member));
        assertThat(HANDLE_CALLS.get()).isEqualTo(1);
    }

    @Test
    void surviving_instance_takes_over_after_the_scheduling_instance_dies() {
        DeadlineQueue queueA = instanceA.getBean(DeadlineQueue.class);
        String member = "handoff-" + UUID.randomUUID();

        // A 가 5초 뒤 만료로 등록한 뒤 즉시 죽는다.
        queueA.schedule("probe", member, Duration.ofSeconds(3));
        instanceA.close();

        // 구 in-memory 구현이었다면 이 타이머는 A 와 함께 영영 사라진다.
        // (실제 탈주 유예에서 "탈주가 확정 안 되는" 버그가 이 형태였다.)
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(HANDLED).contains(member));
        assertThat(HANDLE_CALLS.get()).isEqualTo(1);
    }

    @Test
    void two_polling_instances_never_double_execute() {
        DeadlineQueue queueA = instanceA.getBean(DeadlineQueue.class);
        int n = 50;
        List<String> members = java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "dup-" + i)
                .toList();
        members.forEach(m -> queueA.schedule("probe", m, Duration.ZERO));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(HANDLED).hasSize(n));

        // 두 인스턴스가 동시에 폴링해도 총 실행 횟수가 정확히 n 이어야 한다.
        // 여기서 초과가 나면 실제 게임에서는 "턴이 두 번 넘어감"으로 나타난다.
        assertThat(HANDLE_CALLS.get()).isEqualTo(n);
        assertThat(HANDLED.stream().distinct().count()).isEqualTo(n);
    }

    @Test
    void presence_registered_on_one_instance_is_visible_from_the_other() {
        RoomPresence presenceA = instanceA.getBean(RoomPresence.class);
        RoomPresence presenceB = instanceB.getBean(RoomPresence.class);
        String room = UUID.randomUUID().toString();

        presenceA.join("sess-on-A", 77L, room);

        // 이게 false 면 B 의 탈주 유예가 A 로 재접속한 사람을 탈주 처리한다.
        assertThat(presenceB.hasLiveSession(77L, room)).isTrue();
        assertThat(presenceB.viewers(room)).contains(77L);

        presenceB.leave("sess-on-A");
        assertThat(presenceA.hasLiveSession(77L, room)).isFalse();
    }
}
